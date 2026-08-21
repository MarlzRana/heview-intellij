package com.marlzrana.heview.storage

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.marlzrana.heview.model.CommentJson
import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.model.HeviewComment
import com.marlzrana.heview.model.HeviewReply
import com.marlzrana.heview.model.normalizedReplies
import com.marlzrana.heview.model.recomputed
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory index + JSON persistence for comment threads, mirroring reviewa's `CommentStore`:
 * one file per thread at `<commentsDir>/<uuid>.json`, with the in-memory map as the source of truth.
 *
 * Threading: the index and listeners are touched only on the EDT and update optimistically; disk
 * I/O is offloaded to a serial background executor ([runIo]) so the EDT never blocks and file
 * operations for the same comment can't race. Tests inject a synchronous executor. `delete` mirrors
 * reviewa: the thread leaves the UI immediately and the file is unlinked best-effort in the
 * background (a delete failure is logged, not surfaced). Deliberately free of any IDE/editor
 * dependency so it is unit-testable against a temp directory.
 */
class CommentStore(
    private val commentsDir: Path,
    private val runIo: (Runnable) -> Unit = { IO_POOL.execute(it) },
    private val runEdt: (Runnable) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
    // Only used to reconstruct a single reply for a foreign/legacy file that has no `replies` array;
    // heview v1 is single-author, so the OS user is the right default. Injectable for tests.
    private val defaultAuthor: () -> String = { System.getProperty("user.name") ?: "You" },
) {
    /** Application-service constructor — binds to the shared `~/.heview/comments` pool. */
    @Suppress("unused")
    constructor() : this(HeviewPaths.commentsDir)

    // Where a hook claims a consumed comment (an atomic move out of the pool); a lingering tombstone
    // here means "Seen". Resolved the same way as HeviewPaths.processedDir so re-pend/edit/reply can
    // remove the tombstone when they revive a comment back into comments/.
    private val processedDir = commentsDir.resolve("processed")

    private val index = LinkedHashMap<String, HeviewComment>()
    private val listeners = mutableListOf<() -> Unit>()
    private val hydrated = AtomicBoolean(false)

    // Fired on the EDT once hydrate() has applied its snapshot — lets the consumption watcher run a
    // one-shot catch-up reconcile without subscribing to (and re-scanning on) every store change.
    private var hydrationApplied = false
    private val onHydrated = mutableListOf<() -> Unit>()

    // UUIDs confirmed present on disk in comments/ — a save's write succeeded, or the file was hydrated
    // from disk. The watcher's reconcile uses this to evict only a comment that was persisted and then
    // vanished (a lost bare-delete); a comment whose create-save failed or is still in flight was never
    // persisted, so it is retained (matches the "keep the record on persist failure" decision).
    // Thread-safe: added off-EDT (save's write completion) + on-EDT (hydrate), read off-EDT (reconcile).
    private val persisted = ConcurrentHashMap.newKeySet<String>()

    /** Register a change listener; dispose the returned handle to unregister (e.g. on project close). */
    fun addChangeListener(listener: () -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners.remove(listener) }
    }

    /**
     * Run [callback] on the EDT once [hydrate] has applied its snapshot — immediately if it already has.
     * EDT-only. Used by the consumption watcher to reconcile the pool exactly once after the startup load
     * (a consume that raced hydrate fired its watch event before the record was indexed).
     */
    fun whenHydrated(callback: () -> Unit) {
        if (hydrationApplied) callback() else onHydrated += callback
    }

    // Iterate a copy so a listener may unregister itself (or another) during the callback.
    private fun fireChanged() = listeners.toList().forEach { it() }

    // Safe by construction: uuids only enter the index from save() (a random UUID) or hydrate() (where
    // the uuid must equal a single-component filename), so this never resolves outside commentsDir.
    private fun fileFor(uuid: String): Path = commentsDir.resolve("$uuid.json")

    /**
     * Create (or upsert) a thread: index + fire immediately (EDT), then persist atomically on a background
     * thread. This is the create path (a brand-new random uuid) and the test upsert seam — it is NOT
     * generation-fenced (a fresh uuid has no on-disk peer to race). Reply mutations of an existing thread go
     * through [persistMutation], which reads and re-applies against the on-disk generation.
     */
    fun save(comment: HeviewComment) {
        index[comment.uuid] = comment
        fireChanged()
        val uuid = comment.uuid
        val json = CommentJson.encode(comment)
        runIo {
            if (writeAtomically(uuid, json)) persisted.add(uuid)
        }
    }

    /** True once [uuid] has been confirmed on disk (write succeeded or hydrated) and not since removed. */
    fun isPersisted(uuid: String): Boolean = persisted.contains(uuid)

    /**
     * Rewrite only a thread's durable anchor — its `line_number`/`line_content` — after in-IDE edits moved
     * its line (the manager calls this from `beforeDocumentSaving`, so the pool matches the on-disk file the
     * agent reads; plan.html §5, cycle3 #2). Deliberately narrow:
     * - No-op if unchanged or if the uuid is unknown. Updates the **in-memory** record for a known uuid
     *   unconditionally (a consumed/Seen thread, or a create-save still in flight) so a later re-pend/reopen
     *   uses the moved line.
     * - The write is **always queued** on the serial IO executor — never short-circuited on the EDT — so a
     *   write requested while this thread's create-save is still in flight lands *behind* that create rather
     *   than being dropped (the EDT `isPersisted` would still be false then, silently losing the update).
     *   The IO task then skips unless the thread is still in `comments/` (`isPersisted` AND the file exists):
     *   a location writeback must never recreate a pool file a hook has claimed into `processed/` and
     *   resurrect an injectable duplicate. (`markProcessed` can lag a hook's atomic move by the WatchService
     *   latency; the residual sub-millisecond move-vs-write TOCTOU belongs to the deferred generation fence.)
     * - On a write failure the optimistic in-memory update is reverted, so the next save (with the marker
     *   unchanged) still differs from the index and retries instead of the no-op guard suppressing it forever.
     * - Does NOT fire the change listener: every open card already sits on the live [RangeMarker], so only
     *   the durable copy the injector/reopen reads needs updating — not what any card shows.
     * - Touches only these two fields (never `status`/`replies`/`created_at`), so it can't reorder injection.
     */
    fun updateLocation(uuid: String, line1Based: Int, lineContent: String) {
        val current = index[uuid] ?: return
        if (current.lineNumber == line1Based && current.lineContent == lineContent) return
        val updated = current.copy(lineNumber = line1Based, lineContent = lineContent)
        index[uuid] = updated
        val baseGeneration = current.generation
        runIo {
            if (!isPersisted(uuid)) return@runIo // create-save failed / off-pool — no durable file to update
            // Generation-fenced, no bump (plan.html §5): write the moved anchor only if the on-disk thread is
            // still exactly the generation this update was based on. A mismatch means a peer edited it, OR a
            // hook claimed the file into processed/ (now absent) — either way SKIP, never clobbering a newer
            // thread or resurrecting a consumed one. Encode happens off the EDT inside casWrite (this runs in
            // FileDocumentManager's save write-action; a large thread would otherwise stall the save).
            when (casWrite(uuid, CommentJson.encode(updated), expectedGeneration = baseGeneration)) {
                // A location writeback is re-derivable; RACED leaves the moved line in memory (it did move —
                // a re-pend later uses it) without resurrecting the file. Only a genuine write FAILURE reverts
                // (identity-guarded so it can't undo a newer queued update) so the next save retries.
                CasResult.WROTE, CasResult.RACED -> {}
                CasResult.FAILED -> runEdt { if (index[uuid] === updated) index[uuid] = current }
            }
        }
    }

    /**
     * The per-reply state machine (plan.html §5), each fenced by the pool's optimistic generation CAS.
     * Every op builds a pure, **idempotent** content transform of a thread and hands it to [persistMutation],
     * which applies it optimistically in memory (responsive UI) and then commits it against the *on-disk*
     * thread — re-reading and re-applying the transform by the reply's stable [HeviewReply.id] so a peer's
     * concurrent edit (another client, or a hook consuming the thread mid-edit) is merged, never clobbered.
     *
     * A transform locates its reply by id (not array index or value), returns the SAME base object unchanged
     * when the op is already satisfied (an idempotent re-apply is then a no-op — no write, no fire), or null
     * when its target reply is gone (the thread is reconciled to the on-disk truth). All are no-ops on an
     * unknown uuid or a missing reply; [addReply]/[editReply] also drop blank text.
     *
     * - [addReply] appends a new PENDING reply (the thread's "Leave a comment" box).
     * - [editReply] replaces the target's text and revives it to PENDING (editing a Seen reply makes it
     *   actionable again — reviewa parity).
     * - [rependReply] revives a Seen (PROCESSED) target to PENDING; a no-op if it is already actionable.
     * - [deleteReply] removes the target: the last reply drops the whole thread; if only Seen replies
     *   remain the thread leaves the injectable pool but stays visible (reviewa parity). It does NOT bump
     *   `created_at` (a delete must not reorder the remaining replies' injection — reviewa parity).
     */
    fun addReply(uuid: String, text: String, author: String, now: String) {
        if (text.isBlank()) return
        if (index[uuid] == null) return
        val newReply = HeviewReply(text, CommentStatus.PENDING, author, now)
        persistMutation(uuid) { base ->
            val replies = base.normalizedReplies(author)
            if (replies.any { it.id == newReply.id }) base // already appended (an idempotent re-apply on disk)
            else base.copy(replies = replies + newReply).recomputed(now)
        }
    }

    fun editReply(uuid: String, target: HeviewReply, newText: String, now: String) {
        if (newText.isBlank()) return
        persistMutation(uuid) { base ->
            val replies = base.normalizedReplies(defaultAuthor()).toMutableList()
            val at = replies.indexOfFirst { it.id == target.id }
            when {
                at < 0 -> null // target gone on this base → reconcile to disk
                replies[at].content == newText && replies[at].status == CommentStatus.PENDING -> base // already applied
                else -> {
                    // Copy the CURRENT reply (its id/author), not the possibly-stale target; editing revives it.
                    replies[at] = replies[at].copy(content = newText, status = CommentStatus.PENDING, createdAt = now)
                    base.copy(replies = replies).recomputed(now)
                }
            }
        }
    }

    fun rependReply(uuid: String, target: HeviewReply, now: String) {
        persistMutation(uuid) { base ->
            val replies = base.normalizedReplies(defaultAuthor()).toMutableList()
            val at = replies.indexOfFirst { it.id == target.id }
            when {
                at < 0 -> null // target gone on this base → reconcile to disk
                replies[at].status != CommentStatus.PROCESSED -> base // already actionable (or re-applied) → no-op
                else -> {
                    replies[at] = replies[at].copy(status = CommentStatus.PENDING, createdAt = now)
                    base.copy(replies = replies).recomputed(now)
                }
            }
        }
    }

    fun deleteReply(uuid: String, target: HeviewReply) {
        persistMutation(uuid) { base ->
            val replies = base.normalizedReplies(defaultAuthor()).toMutableList()
            val at = replies.indexOfFirst { it.id == target.id }
            if (at < 0) return@persistMutation null // already removed / not present → reconcile to disk
            replies.removeAt(at) // remove exactly the matched reply, not every reply sharing the id
            // Preserve created_at — deleting a reply must not reorder the thread's injection (reviewa parity).
            base.copy(replies = replies).recomputed(base.createdAt)
        }
    }

    /**
     * Apply an idempotent, content-only [transform] to a thread and re-persist it under the pool's optimistic
     * generation CAS (plan.html §5 "generation fence").
     *
     * The UI is optimistic: apply the transform to the in-memory record and fire immediately (generation
     * bumped so a burst of edits on the same thread chains monotonically and the durable commit's in-memory
     * adoption never regresses a newer edit). The DURABLE decision is then made on the IO thread against the
     * *on-disk* thread ([commitMutation]) so a peer's concurrent edit is merged rather than clobbered. A
     * transform that returns the base unchanged (`===`) is a no-op — no fire, no write; one that returns null
     * (its target reply is gone in the in-memory record) is a no-op too.
     */
    private fun persistMutation(uuid: String, transform: (HeviewComment) -> HeviewComment?) {
        val current = index[uuid] ?: return
        val optimistic = transform(current) ?: return
        if (optimistic === current) return // idempotent no-op (e.g. re-pend of an already-pending reply)
        if (optimistic.replies.isNullOrEmpty()) index.remove(uuid)
        else index[uuid] = optimistic.copy(generation = current.generation + 1)
        fireChanged()
        commitMutation(uuid, transform, fallback = current, attemptsLeft = MAX_CAS_ATTEMPTS)
    }

    /**
     * The durable half of [persistMutation], on the IO thread. Reads the on-disk thread, re-applies
     * [transform] to it (merging a peer's edit) and — by the merged result's shape ([dispatchMerged]) —
     * writes it back under a generation CAS, or drops / off-pools the thread. [fallback] is the in-memory
     * base used when the pool file is absent (a revive that legitimately resurrects a consumed/never-persisted
     * thread). Retries on a CAS race (a peer wrote between our read and our move) up to [attemptsLeft].
     */
    private fun commitMutation(
        uuid: String,
        transform: (HeviewComment) -> HeviewComment?,
        fallback: HeviewComment,
        attemptsLeft: Int,
    ) {
        runIo {
            val disk = readDisk(uuid)
            if (disk is DiskRead.Unreadable) {
                // A foreign writer left an unparseable file — never clobber it. Leave the optimistic memory
                // state; the next mutation retries against whatever is then on disk.
                LOG.warn("heview: skipping a fenced write over an unreadable pool file $uuid")
                return@runIo
            }
            val onDisk = (disk as? DiskRead.Valid)?.comment
            val base = onDisk ?: fallback
            val merged = transform(base)
            if (merged == null || (onDisk != null && merged === base)) {
                // The target reply is gone on disk, or the op is already reflected there (idempotent
                // re-apply) — adopt the on-disk truth (also folds in any peer edit to other replies).
                reconcileToDisk(uuid, disk)
                return@runIo
            }
            dispatchMerged(uuid, transform, fallback, merged, base.generation, onDisk?.generation, attemptsLeft)
        }
    }

    /** On the IO thread: give the merged thread its durable shape — delete, off-pool, or a fenced write. */
    private fun dispatchMerged(
        uuid: String,
        transform: (HeviewComment) -> HeviewComment?,
        fallback: HeviewComment,
        merged: HeviewComment,
        baseGeneration: Int,
        expectedGeneration: Int?,
        attemptsLeft: Int,
    ) {
        when {
            merged.replies.isNullOrEmpty() -> {
                // No replies remain → drop the thread + its pool file + any tombstone (an explicit delete
                // leaves no restorable copy). `merged` came from the on-disk base, so a peer's concurrent
                // reply would have left it non-empty — this never nukes a reply the peer just added.
                unlinkPoolFile(uuid)
                deleteTombstone(uuid)
                runEdt { if (index.remove(uuid) != null) fireChanged() }
            }
            merged.status == CommentStatus.PROCESSED -> {
                // Only Seen replies remain → keep them visible but off the injectable pool (reviewa's
                // deleteComment-with-no-actionable). No tombstone is written, so it is not restored on restart.
                unlinkPoolFile(uuid)
                runEdt { adoptIfNotNewer(uuid, merged) }
            }
            else -> {
                val toWrite = merged.copy(generation = baseGeneration + 1)
                when (casWrite(uuid, CommentJson.encode(toWrite), expectedGeneration)) {
                    CasResult.WROTE -> {
                        persisted.add(uuid)
                        deleteTombstone(uuid) // revived back into the pool → clear the watcher's "Seen" signal
                        runEdt { adoptIfNotNewer(uuid, toWrite) }
                    }
                    CasResult.RACED ->
                        if (attemptsLeft > 1) commitMutation(uuid, transform, fallback, attemptsLeft - 1)
                        else LOG.warn("heview: gave up a fenced write for $uuid after $MAX_CAS_ATTEMPTS attempts")
                    CasResult.FAILED -> {} // leave the optimistic memory state; the next mutation retries
                }
            }
        }
    }

    /**
     * Unlink only the live pool file `comments/<uuid>.json` (never a tombstone). On the IO thread. Clears
     * `persisted` first (it runs on the serial IO executor, so it orders AFTER any in-flight save's
     * `persisted.add`) so the watcher's delete handler — also gated on `isPersisted` — reads the vanish as
     * our own off-pool/delete, not a peer delete.
     */
    private fun unlinkPoolFile(uuid: String) {
        persisted.remove(uuid)
        try {
            Files.deleteIfExists(fileFor(uuid))
        } catch (e: IOException) {
            LOG.warn("heview: failed to unlink the comment file $uuid", e)
        }
    }

    /**
     * Adopt [value] into the index (on the EDT) unless a newer optimistic edit is already there — monotonic
     * on `generation`, so a durable commit never regresses a fresher in-flight mutation. Fires only if the
     * record actually changed (the common no-peer path already shows the optimistic value → no extra fire).
     */
    private fun adoptIfNotNewer(uuid: String, value: HeviewComment) {
        val current = index[uuid]
        if ((current == null || current.generation <= value.generation) && current != value) {
            index[uuid] = value
            fireChanged()
        }
    }

    /** Reconcile the in-memory record to the on-disk truth (a peer removed our target, or already applied it). */
    private fun reconcileToDisk(uuid: String, disk: DiskRead) {
        runEdt {
            when (disk) {
                is DiskRead.Valid -> {
                    persisted.add(uuid)
                    adoptIfNotNewer(uuid, disk.comment)
                }
                DiskRead.Absent -> if (index.remove(uuid) != null) {
                    persisted.remove(uuid)
                    fireChanged()
                }
                DiskRead.Unreadable -> {}
            }
        }
    }

    /** Delete a consumption tombstone; runs on the IO thread (call from within a runIo task). */
    private fun deleteTombstone(uuid: String) {
        // Defence-in-depth (belt to hydrate's uuid==filename check): never resolve a uuid that isn't a
        // single path component, so this can't delete a `.json` outside processed/ via `../` traversal.
        if (uuid.contains('/') || uuid.contains('\\') || uuid == "." || uuid == "..") return
        try {
            // Never follow a symlinked processed/ (matches the injector + sweep guards): a planted
            // `processed -> ~/.codex` link would make this delete a file OUTSIDE ~/.heview.
            if (Files.isSymbolicLink(processedDir)) return
            Files.deleteIfExists(processedDir.resolve("$uuid.json"))
        } catch (e: IOException) {
            LOG.warn("heview: failed to remove the consumption tombstone for $uuid", e)
        }
    }

    /**
     * Load existing threads from the shared pool into the index — once per application. Runs at most
     * once: the read happens on the background executor, then the index is populated and listeners
     * fired back on the EDT ([runEdt]). In-memory records win over disk (a record created this
     * session is authoritative), so hydrating after some saves never clobbers newer state.
     */
    fun hydrate() {
        if (!hydrated.compareAndSet(false, true)) return
        runIo {
            val loaded = readAllFromDisk()
            runEdt {
                var changed = false
                for (comment in loaded) {
                    if (index.putIfAbsent(comment.uuid, comment) == null) changed = true
                    persisted.add(comment.uuid) // it was just read from disk, so its file exists
                }
                if (changed) fireChanged()
                hydrationApplied = true
                onHydrated.toList().forEach { it() }
                onHydrated.clear()
            }
        }
    }

    /**
     * Read, decode and validate every `<uuid>.json` in the pool. Because the pool is durable and shared
     * with foreign writers (reviewa, coding agents), each file is treated as untrusted: anything
     * unreadable, schema-incomplete, filename/uuid-mismatched, or already `processed` is skipped.
     */
    private fun readAllFromDisk(): List<HeviewComment> {
        if (!Files.isDirectory(commentsDir)) return emptyList()
        return try {
            Files.newDirectoryStream(commentsDir, "*.json").use { stream ->
                stream.mapNotNull { path -> decodeIfValid(path) }
            }
        } catch (e: Exception) {
            // Never let one bad file or listing error strand the once-only hydrate; log and move on.
            LOG.warn("heview: failed to read the comments directory $commentsDir", e)
            emptyList()
        }
    }

    private fun decodeIfValid(path: Path): HeviewComment? {
        val expectedUuid = path.fileName.toString().removeSuffix(".json")
        // Nullable: Gson returns null (without throwing) for a literal `null` payload, and decode's
        // declared non-null type does not insert a runtime check — so guard explicitly below.
        val comment: HeviewComment? = try {
            CommentJson.decode(Files.readString(path))
        } catch (e: Exception) {
            LOG.warn("heview: skipping unreadable comment file $path", e)
            return null
        }
        if (comment == null || !isWellFormed(comment, expectedUuid)) {
            // Rejects schema-incomplete JSON (Gson leaves absent required fields null) and files whose
            // payload uuid doesn't match the filename — the latter both prevents `fileFor` path traversal
            // (e.g. a uuid of "../../.codex/hooks") and stops a stale file resurrecting on every restart.
            LOG.warn("heview: skipping malformed or unsafe comment file $path")
            return null
        }
        if (comment.replies.isNullOrEmpty() && comment.status == CommentStatus.PROCESSED) {
            // plan.html §5: a processed comment is not actionable — never hydrate one. A file that carries
            // a replies[] array is instead re-derived below (its top-level status may be stale), so a
            // processed file that still holds a PENDING reply loads as pending; the derived-PROCESSED
            // check after re-derivation drops the genuinely fully-Seen ones.
            return null
        }
        return try {
            // Normalize the reply list (drop malformed replies; reconstruct one from `content` for a
            // reviewa/legacy/pre-`replies` file) and RE-DERIVE the hook-facing content/status from it, so
            // a foreign/tampered file whose top-level fields disagree with its replies can't feed a stale
            // comment to the hooks. created_at is preserved (hydrate must not reorder injection). Wrapped
            // so one file with weird `replies` (e.g. a null element) is skipped, not the whole pool.
            val normalized = comment.copy(replies = sanitizedReplies(comment)).recomputed(comment.createdAt)
            // If the replies say the thread is fully Seen, it isn't actionable — don't hydrate it
            // (consistent with the top-level `processed` skip above).
            if (normalized.status == CommentStatus.PROCESSED) null else normalized
        } catch (e: Exception) {
            LOG.warn("heview: skipping comment with unreadable replies $path", e)
            null
        }
    }

    private fun sanitizedReplies(c: HeviewComment): List<HeviewReply> {
        // Gson (via Unsafe) can leave a null element in a non-null-typed List (null-check each), and
        // bypasses the constructor so an absent/null `id` in a foreign file stays null. Backfill a fresh
        // id for any reply whose id is null OR a duplicate of an earlier one, so every indexed reply has a
        // distinct, matchable identity (else `indexOfFirst { it.id == target.id }` would collapse rows).
        val seenIds = HashSet<String>()
        @Suppress("SENSELESS_COMPARISON")
        val valid = c.replies
            ?.filter { it != null && isWellFormedReply(it) }
            ?.map { r ->
                if (r.id != null && seenIds.add(r.id)) r
                else r.copy(id = java.util.UUID.randomUUID().toString()).also { seenIds.add(it.id) }
            }
            .orEmpty()
        if (valid.isNotEmpty()) return valid
        return listOf(HeviewReply(content = c.content, status = c.status, author = defaultAuthor(), createdAt = c.createdAt, id = c.uuid))
    }

    // A well-formed reply needs its required content fields; a missing `id` is backfilled in [sanitizedReplies].
    @Suppress("SENSELESS_COMPARISON")
    private fun isWellFormedReply(r: HeviewReply): Boolean =
        r.content != null && r.status != null && r.author != null && r.createdAt != null

    // Gson (via Unsafe) can leave a non-null Kotlin field null when its JSON key is absent, and returns
    // null for an unknown enum value — so every required field is null-checked despite its declared type.
    @Suppress("SENSELESS_COMPARISON")
    private fun isWellFormed(c: HeviewComment, expectedUuid: String): Boolean =
        c.uuid == expectedUuid &&
            c.status != null && c.side != null &&
            c.createdAt != null && c.workspace != null &&
            c.absPath != null && c.logicalAbsPath != null &&
            c.content != null && c.lineContent != null &&
            c.lineNumber >= 1

    fun get(uuid: String): HeviewComment? = index[uuid]

    fun all(): List<HeviewComment> = index.values.toList()

    /**
     * Comments anchored to [absPath], matched by normalized path so an editor's `toNioPath()` string
     * lines up with a stored `abs_path` regardless of `..`/redundant separators. Foreign pool writers
     * (another agent, reviewa) may use a different textual form for the same file.
     */
    fun forAbsPath(absPath: String): List<HeviewComment> {
        val target = normalizePath(absPath)
        return index.values.filter { normalizePath(it.absPath) == target }
    }

    fun pendingCount(): Int = index.values.count { it.status == CommentStatus.PENDING }

    fun processedCount(): Int = index.values.count { it.status == CommentStatus.PROCESSED }

    /**
     * Flip a whole thread to "Seen" in memory — for the consumption watcher, when an agent hook has
     * consumed the file (moved it into `processed/`). Every reply becomes PROCESSED and the thread's
     * derived `content` empties, so all rows relabel to Seen at once (each then offering re-pend).
     *
     * Deliberately does NOT persist: a processed thread is not written to the pool (plan.html §5), and
     * its file has already left `comments/`, so re-saving here would resurrect an injectable duplicate.
     * No-op if the uuid is unknown or already fully processed.
     */
    fun markProcessed(uuid: String) {
        val current = index[uuid] ?: return
        if (current.status == CommentStatus.PROCESSED) return
        val seen = current.normalizedReplies(defaultAuthor()).map { it.copy(status = CommentStatus.PROCESSED) }
        // recomputed derives the empty content + PROCESSED status (all replies Seen); preserve created_at.
        index[uuid] = current.copy(replies = seen).recomputed(current.createdAt)
        persisted.remove(uuid) // the file has left comments/ (moved to processed/)
        fireChanged()
    }

    /**
     * Drop a record from the index and fire, WITHOUT touching disk — for the watcher when a comment's
     * file simply vanished from the pool (a peer/user delete), where there is nothing left to unlink.
     * No-op if the uuid is unknown (so a self-[delete] the watcher later observes is idempotent).
     */
    fun evict(uuid: String) {
        if (index.remove(uuid) == null) return
        persisted.remove(uuid)
        fireChanged()
    }

    /**
     * Remove an **orphaned** comment — its commented line was deleted or externally rewritten, so a reload
     * left its anchor drifted onto unrelated code (the manager's reload path; plan.html §5 session-lifetime
     * exception). Like [evict] it drops the in-memory record + fires, and it unlinks the live pool file
     * `comments/<uuid>.json` so the comment is gone for the injector + peers — but, unlike [delete], it
     * **never removes a `processed/` tombstone**.
     *
     * This is the safe difference from [delete]: a hook consumes a comment by an atomic *move* into
     * `processed/`, and in the sliver between that move and the watcher's [markProcessed] the in-memory
     * status still reads PENDING, so a reload could classify the just-consumed thread as an orphan. Unlinking
     * only the live file (a no-op if the hook already moved it away) preserves the tombstone, so the consume
     * still resolves to *Seen* for peers + a restart rather than being mistaken for a user delete. A genuine
     * orphan (never consumed) has no tombstone, so it simply vanishes — peers `evict` it, which is correct:
     * binning means "gone everywhere," the same end state as an explicit [delete]. (The residual window where
     * this client drops a raced-consume thread from its own index belongs to the deferred generation fence.)
     */
    fun binFromPool(uuid: String) {
        if (index.remove(uuid) == null) return
        fireChanged()
        runIo {
            // Serial IO executor so it orders after any in-flight save's persisted.add (see delete()).
            persisted.remove(uuid)
            try {
                // Unlink ONLY the live comment file, never the processed/ tombstone. deleteIfExists is a
                // no-op if a hook already claimed the file into processed/ — so a raced consume keeps its
                // tombstone (and stays Seen for peers/restart) instead of being wiped.
                Files.deleteIfExists(fileFor(uuid))
            } catch (e: IOException) {
                LOG.warn("heview: failed to unlink the orphaned comment file $uuid", e)
            }
        }
    }

    /** Drop the record and fire immediately; unlink the pool file (and any tombstone) in the background. */
    fun delete(uuid: String) {
        if (index.remove(uuid) == null) return
        fireChanged()
        runIo {
            // On the serial IO executor so it orders AFTER any in-flight save's persisted.add for this uuid
            // (an EDT-side remove could be undone by a late save completion; harmless but a leak).
            persisted.remove(uuid)
            try {
                Files.deleteIfExists(fileFor(uuid))
            } catch (e: IOException) {
                LOG.warn("heview: failed to delete comment file $uuid", e)
            }
            // Also drop a consumption tombstone if the thread had been consumed — an explicit delete
            // leaves no restorable copy (symmetric with revive's care; deleteTombstone refuses a symlink).
            deleteTombstone(uuid)
        }
    }

    /**
     * Create-or-replace write for the create/upsert path ([save]): a temp file in the same directory, then
     * an atomic move over the target. Returns true once the comment is on disk — the caller records it
     * persisted only then, so a failed write keeps the in-memory record without the watcher later treating
     * the missing file as a delete. Fenced reply mutations use [casWrite] instead.
     */
    private fun writeAtomically(uuid: String, json: String): Boolean {
        return try {
            Files.createDirectories(commentsDir)
            val tmp = Files.createTempFile(commentsDir, uuid, ".json.tmp")
            try {
                Files.writeString(tmp, json)
                moveOntoTarget(tmp, uuid)
                true
            } catch (e: IOException) {
                Files.deleteIfExists(tmp) // don't leak a stray .json.tmp into the shared pool
                throw e
            }
        } catch (e: IOException) {
            LOG.warn("heview: failed to persist comment $uuid", e)
            false
        }
    }

    /**
     * Write [json] over `comments/<uuid>.json`, but only if the on-disk generation still equals
     * [expectedGeneration] (null means "expect the file ABSENT" — a resurrecting revive). The generation is
     * re-read as close to the atomic move as possible; a mismatch — a peer wrote a newer generation, a hook
     * claimed the file into `processed/` (now absent), or a foreign writer left it unreadable — returns
     * [CasResult.RACED] without writing, so the caller re-reads and re-merges. The residual window between
     * this re-check and the move is the accepted optimistic-CAS sliver (plan.html §5 "generation fence").
     */
    private fun casWrite(uuid: String, json: String, expectedGeneration: Int?): CasResult {
        return try {
            Files.createDirectories(commentsDir)
            val tmp = Files.createTempFile(commentsDir, uuid, ".json.tmp")
            try {
                Files.writeString(tmp, json)
                val matches = when (val onDisk = readDisk(uuid)) {
                    DiskRead.Absent -> expectedGeneration == null
                    is DiskRead.Valid -> expectedGeneration != null && onDisk.comment.generation == expectedGeneration
                    DiskRead.Unreadable -> false
                }
                if (!matches) {
                    Files.deleteIfExists(tmp) // a peer/hook changed the file under us — don't clobber it
                    return CasResult.RACED
                }
                moveOntoTarget(tmp, uuid)
                CasResult.WROTE
            } catch (e: IOException) {
                Files.deleteIfExists(tmp)
                throw e
            }
        } catch (e: IOException) {
            LOG.warn("heview: failed to persist comment $uuid", e)
            CasResult.FAILED
        }
    }

    /** Atomic move of [tmp] over `comments/<uuid>.json`, falling back to a non-atomic replace off-POSIX. */
    private fun moveOntoTarget(tmp: Path, uuid: String) {
        try {
            Files.move(tmp, fileFor(uuid), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // ATOMIC_MOVE unsupported, or the target exists on a non-POSIX FS → non-atomic replace. On the
            // POSIX targets we support (macOS/Linux) the atomic move already replaces, so this fallback is
            // only reached off-platform.
            Files.move(tmp, fileFor(uuid), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Read + validate + normalize the on-disk thread for [uuid] — the CAS generation source and the merge
     * base. Absent (no file), Valid (a well-formed, replies-normalized thread) or Unreadable (present but
     * schema-broken / unparseable — never clobbered).
     */
    private fun readDisk(uuid: String): DiskRead {
        val path = fileFor(uuid)
        if (!Files.exists(path)) return DiskRead.Absent
        return try {
            // Nullable: Gson returns null (without throwing) for a literal `null` payload; decode's declared
            // non-null type inserts no runtime check, so guard explicitly (matches decodeIfValid).
            val c: HeviewComment? = CommentJson.decode(Files.readString(path))
            if (c != null && isWellFormed(c, uuid)) DiskRead.Valid(c.copy(replies = sanitizedReplies(c)))
            else DiskRead.Unreadable
        } catch (e: Exception) {
            DiskRead.Unreadable
        }
    }

    /** The on-disk state of a pool file at read time (see [readDisk]). */
    private sealed interface DiskRead {
        object Absent : DiskRead
        data class Valid(val comment: HeviewComment) : DiskRead
        object Unreadable : DiskRead
    }

    /** Outcome of a generation-fenced [casWrite]. */
    private enum class CasResult { WROTE, RACED, FAILED }

    /** Normalize for path comparison; fall back to the raw string if it isn't a valid path. */
    private fun normalizePath(path: String): String =
        try {
            Path.of(path).normalize().toString()
        } catch (e: InvalidPathException) {
            path
        }

    companion object {
        private val LOG = logger<CommentStore>()

        // How many times a fenced reply mutation re-reads + re-merges when a peer writes between our read
        // and our atomic move. A tiny bound — real contention on one thread's file is microsecond-narrow —
        // that stops a pathological write storm from looping forever; giving up just leaves the optimistic
        // in-memory state, which the next mutation retries.
        private const val MAX_CAS_ATTEMPTS = 4

        // Single-threaded so disk writes/deletes for the same comment can't race. The platform
        // requires a capitalized pool name (BoundedTaskExecutor asserts this).
        private val IO_POOL by lazy {
            AppExecutorUtil.createBoundedApplicationPoolExecutor("Heview comment IO", 1)
        }
    }
}
