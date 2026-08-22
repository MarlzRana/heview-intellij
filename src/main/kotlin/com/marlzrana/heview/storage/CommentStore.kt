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
     *   than being dropped. It then skips unless the thread is still persisted (`isPersisted`), and is
     *   **generation-fenced with no bump** (`casWrite` against the current generation): it commits the moved
     *   anchor only if the on-disk generation still matches, so it never clobbers a peer's newer edit nor
     *   recreates a pool file a hook has claimed into `processed/` (that shows as Absent → a CAS miss).
     * - On a non-WROTE result, revert the optimistic move (identity-guarded so it can't undo a newer queued
     *   update) ONLY IF the pool file is still present — a peer bumped the generation, or a genuine write
     *   failure — so the no-op early-return above can't see memory already matching the marker and suppress the
     *   next save's retry, which would strand the on-disk line. If the file is GONE (a hook consumed it into
     *   `processed/`, or a peer deleted it), KEEP the moved line: there is no disk to heal, and `markProcessed`
     *   then freezes THIS anchor onto the Seen thread so a later re-pend injects the line the marker moved to.
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
            // FileDocumentManager's save write-action; a large thread would otherwise stall the save). Revert
            // the optimistic move only if the write didn't land AND the file is still present (peer/failure) —
            // so the next save retries; a consumed/deleted file (now Absent) keeps the moved line (see KDoc).
            if (casWrite(uuid, CommentJson.encode(updated), expectedGeneration = baseGeneration) != CasResult.WROTE &&
                Files.exists(fileFor(uuid))
            ) {
                runEdt { if (index[uuid] === updated) index[uuid] = current }
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
        val newReply = HeviewReply(text, CommentStatus.PENDING, author, now) // persistMutation no-ops an unknown uuid
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
     * The UI is optimistic: apply the transform to the in-memory record and fire immediately. The durable
     * decision is then made on the IO thread against the *on-disk* thread ([commitMutation]) so a peer's
     * concurrent edit is merged rather than clobbered. The commit adopts its result back into the index only
     * if [staged] — the exact object this optimistic step placed there — is STILL the current entry (an
     * identity `===` guard), so a newer reply mutation, a no-bump [updateLocation], or a delete/bin that
     * replaced/removed it wins over this (older) commit. A transform that returns the base unchanged (`===`)
     * is a no-op — no fire, no write; one that returns null (its target reply is gone in memory) is too.
     */
    private fun persistMutation(uuid: String, transform: (HeviewComment) -> HeviewComment?) {
        val current = index[uuid] ?: return
        val optimistic = transform(current) ?: return
        if (optimistic === current) return // idempotent no-op (e.g. re-pend of an already-pending reply)
        // Snapshot pool membership NOW, on the EDT, before a racing markProcessed/evict can clear `persisted`:
        // the commit uses this — not the live isPersisted — to tell a legitimate resurrect (an off-pool re-pend)
        // from a consume/delete that must NOT be resurrected.
        val wasOnPool = isPersisted(uuid)
        // The exact object placed in the index (null once the thread went empty) — the commit's identity guard.
        val staged = if (optimistic.replies.isNullOrEmpty()) null else optimistic.copy(generation = current.generation + 1)
        if (staged == null) index.remove(uuid) else index[uuid] = staged
        fireChanged()
        commitMutation(uuid, transform, current, staged, wasOnPool)
    }

    /**
     * The durable half of [persistMutation], on the IO thread. In a bounded **synchronous** loop it reads the
     * on-disk thread, re-applies [transform] to it (merging a peer's edit) and — by the merged result's shape
     * ([dispatchMerged]) — writes it back under a generation CAS, or drops / off-pools the thread. On a CAS
     * race (a peer wrote between our read and our move) it re-reads + re-applies IN PLACE, up to
     * [MAX_CAS_ATTEMPTS]. The retry stays inside this single serial-IO task ON PURPOSE: re-queueing it would
     * let it run behind a later mutation on the same uuid, so a stale re-merge could overwrite the newer
     * write. [fallback] is the in-memory base used when the pool file is absent (a legitimate resurrect of a
     * consumed/never-persisted off-pool thread). [wasOnPool] is `isPersisted` snapshotted on the EDT at
     * optimistic time — the resurrect oracle (see below), since the live flag can be cleared by a racing
     * markProcessed/evict.
     */
    private fun commitMutation(
        uuid: String,
        transform: (HeviewComment) -> HeviewComment?,
        fallback: HeviewComment,
        staged: HeviewComment?,
        wasOnPool: Boolean,
    ) {
        runIo {
            for (attempt in 1..MAX_CAS_ATTEMPTS) {
                val onDisk: HeviewComment? = when (val disk = readDisk(uuid)) {
                    // A foreign writer left an unparseable file — never clobber it. Leave the optimistic memory
                    // state; the next mutation retries against whatever is then on disk.
                    DiskRead.Unreadable -> {
                        LOG.warn("heview: skipping a fenced write over an unreadable pool file $uuid")
                        return@runIo
                    }
                    // The pool file vanished under us. If it was on-pool when we started ([wasOnPool] — snapshotted
                    // on the EDT before a racing markProcessed/evict could clear isPersisted), a hook claimed it
                    // into processed/ or a peer/local delete unlinked it — NEVER resurrect it (that would recreate
                    // an injectable duplicate and deleteTombstone would wipe the watcher's "Seen" signal, or undo
                    // the delete). Leave the optimistic memory for the watcher to reconcile. Only an already-off-
                    // pool thread (!wasOnPool — e.g. a re-pend of a Seen thread) legitimately resurrects here.
                    DiskRead.Absent -> if (wasOnPool) return@runIo else null
                    is DiskRead.Valid -> disk.comment
                }
                val base = onDisk ?: fallback
                val merged = transform(base)
                if (merged == null || (onDisk != null && merged === base)) {
                    // The target reply is gone on disk, or the op is already reflected there (idempotent
                    // re-apply) — adopt the on-disk truth (also folds in any peer edit to other replies).
                    reconcileToDisk(uuid, staged, onDisk)
                    return@runIo
                }
                when (dispatchMerged(uuid, staged, merged, base.generation, onDisk?.generation)) {
                    CasResult.WROTE, CasResult.FAILED -> return@runIo // done, or leave optimistic for the next save
                    CasResult.RACED -> Unit // a peer wrote in our CAS window → loop: re-read + re-merge
                }
            }
            LOG.warn("heview: gave up a fenced write for $uuid after $MAX_CAS_ATTEMPTS attempts")
        }
    }

    /**
     * On the IO thread: give the merged thread its durable shape — delete, off-pool, or a fenced write —
     * returning the [CasResult] so [commitMutation] can re-merge a RACED write in its synchronous loop.
     */
    private fun dispatchMerged(
        uuid: String,
        staged: HeviewComment?,
        merged: HeviewComment,
        baseGeneration: Int,
        expectedGeneration: Int?,
    ): CasResult = when {
        merged.replies.isNullOrEmpty() -> fencedDestructive(uuid, expectedGeneration).also {
            if (it == CasResult.WROTE) {
                // No replies remain → drop the thread + its pool file + any tombstone (an explicit delete
                // leaves no restorable copy). The unlink is generation-fenced, so a peer's reply that landed
                // since our read RACES and re-merges (keeping the file) rather than being nuked.
                unlinkPoolFile(uuid)
                deleteTombstone(uuid)
                runEdt { if (stillCurrent(uuid, staged) && index.remove(uuid) != null) fireChanged() }
            }
        }
        merged.status == CommentStatus.PROCESSED -> fencedDestructive(uuid, expectedGeneration).also {
            if (it == CasResult.WROTE) {
                // Only Seen replies remain → keep them visible but off the injectable pool (reviewa's
                // deleteComment-with-no-actionable). No tombstone is written, so it is not restored on restart.
                unlinkPoolFile(uuid)
                runEdt { adoptIfUnchanged(uuid, staged, merged) }
            }
        }
        else -> {
            val toWrite = merged.copy(generation = baseGeneration + 1)
            casWrite(uuid, CommentJson.encode(toWrite), expectedGeneration).also {
                if (it == CasResult.WROTE) {
                    persisted.add(uuid)
                    deleteTombstone(uuid) // revived back into the pool → clear the watcher's "Seen" signal
                    runEdt { adoptIfUnchanged(uuid, staged, toWrite) }
                }
            }
        }
    }

    /**
     * Generation check for a destructive unlink (delete-thread / off-pool). OK to unlink only if the on-disk
     * thread is still the generation we merged from — a peer that added a reply since bumped it → [RACED] →
     * re-merge so the reply survives rather than being nuked (the peer-overwrite the fence must close on this
     * path too). Absent = already gone (OK). A null [expectedGeneration] means the thread was already off-pool
     * when we read it, so there is nothing to nuke (OK). Re-read as close to the unlink as possible; the same
     * microsecond residual as [casWrite] remains (the accepted optimistic-CAS window).
     */
    private fun fencedDestructive(uuid: String, expectedGeneration: Int?): CasResult =
        // Strict CAS on the current disk state (no early-exit that skips the read): unlink only if the file
        // matches what we merged from. A peer that resurrected an off-pool file (was absent, now present) or
        // bumped a present one → RACED → re-merge, not a blind unlink. A file expected present but now Absent
        // (a hook claimed it) → RACED, so we never deleteTombstone away the "Seen" signal.
        if (matchesGeneration(readDisk(uuid), expectedGeneration)) CasResult.WROTE else CasResult.RACED

    /**
     * Does the current on-disk state match the generation a fenced write/unlink expects? A null
     * [expectedGeneration] means "expect the file ABSENT" (a resurrecting revive, or an already-off-pool
     * destructive op); a non-null one must match a present file's generation exactly. An unreadable file never
     * matches (never clobber a foreign writer's file).
     */
    private fun matchesGeneration(onDisk: DiskRead, expectedGeneration: Int?): Boolean = when (onDisk) {
        DiskRead.Absent -> expectedGeneration == null
        is DiskRead.Valid -> expectedGeneration != null && onDisk.comment.generation == expectedGeneration
        DiskRead.Unreadable -> false
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
     * Is OUR optimistic record still the current index entry — i.e. no newer reply mutation, delete, or
     * bin/evict has superseded it? Primarily an identity (`===`) guard, since generation alone can't order
     * our optimistic bump against a peer's independent bump. It ALSO accepts a same-generation
     * location-variant of [staged]: a no-bump [updateLocation] that ran on the EDT between our optimistic
     * apply and our commit replaces the index object in place (identical but for `line_number`/`line_content`
     * at the same generation) — that is still "ours", and [adoptIfUnchanged] carries the moved anchor onto the
     * durable value so neither the location nor the reply-merge/generation is stranded. [staged] == null means
     * this op optimistically REMOVED the thread (a delete of its last reply), so "still current" = still absent.
     */
    private fun stillCurrent(uuid: String, staged: HeviewComment?): Boolean {
        val current = index[uuid]
        return when {
            staged == null -> current == null
            current == null -> false
            current === staged -> true
            else -> current.generation == staged.generation &&
                current == staged.copy(lineNumber = current.lineNumber, lineContent = current.lineContent)
        }
    }

    /**
     * Adopt [value] into the index (on the EDT) only if [stillCurrent] — so a durable commit never regresses a
     * fresher in-flight state. [value] keeps the on-disk anchor it was merged from, NOT the current in-memory
     * line: memory tracks disk, so a concurrent [updateLocation] that moved the line heals disk via its own
     * writeback (or the next save's), rather than this leaving memory ahead of disk where the no-op
     * early-return would suppress the heal and strand the injector on a stale line. When our op emptied the
     * thread ([staged] == null) but the on-disk merge came back non-empty (a peer added a reply), this INSERTs
     * the surviving thread so the UI shows the peer's reply instead of the thread vanishing until a restart.
     * Fires only on a real change (the common no-peer path already shows the optimistic value → no extra fire).
     */
    private fun adoptIfUnchanged(uuid: String, staged: HeviewComment?, value: HeviewComment) {
        if (stillCurrent(uuid, staged) && index[uuid] != value) {
            index[uuid] = value
            fireChanged()
        }
    }

    /**
     * Reconcile the in-memory record to the on-disk truth (a peer removed our target, or already applied our
     * op). commitMutation only reaches here with a **Valid** disk thread ([onDisk] non-null) — an Absent disk
     * can't produce a null/idempotent merge (the optimistic transform already succeeded on the same base) — so
     * a null [onDisk] is a defensive no-op. Folds disk back in only if our optimistic record is still current
     * (else a newer edit / delete wins).
     */
    private fun reconcileToDisk(uuid: String, staged: HeviewComment?, onDisk: HeviewComment?) {
        if (onDisk == null) return
        runEdt {
            if (stillCurrent(uuid, staged)) {
                persisted.add(uuid)
                adoptIfUnchanged(uuid, staged, onDisk)
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
        // bypasses the constructor so an absent/null `id` in a foreign file stays null. Backfill an id for any
        // reply whose id is null OR a duplicate of an earlier one, so every indexed reply has a distinct,
        // matchable identity (else `indexOfFirst { it.id == target.id }` would collapse rows).
        //
        // The backfill must be **deterministic** — keyed by thread-uuid + position, NOT a random UUID. The
        // generation-fenced CAS merge re-reads the pool file on every attempt and matches replies by id, so a
        // foreign/legacy file with no/duplicate ids must yield the SAME id on hydrate AND on every readDisk;
        // a random id per read would never match a target captured earlier, so an edit's durable re-apply
        // would silently fail to persist. Position is stable until the first write persists these ids as real.
        val seenIds = HashSet<String>()
        @Suppress("SENSELESS_COMPARISON")
        val valid = c.replies
            ?.filter { it != null && isWellFormedReply(it) }
            ?.mapIndexed { idx, r ->
                if (r.id != null && seenIds.add(r.id)) {
                    r
                } else {
                    var backfilled = "${c.uuid}#$idx"
                    while (!seenIds.add(backfilled)) backfilled += "#" // disambiguate a rare clash with a kept id
                    r.copy(id = backfilled)
                }
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

    // @TestOnly seam: invoked inside stagedWrite right before the pre-move accept check, so a test can
    // interpose a concurrent peer write into the CAS window and exercise the RACED retry / give-up path —
    // that window lives inside one serial IO task, so the runIo task-queue seam alone can't reach it. Null in
    // production.
    @org.jetbrains.annotations.TestOnly
    internal var interposeBeforeMoveForTest: (() -> Unit)? = null

    /**
     * Stage [json] to a temp file in the pool dir and atomically move it over `comments/<uuid>.json`, but only
     * if [acceptMove] — evaluated as close to the move as possible — returns true. Returns [CasResult.WROTE]
     * once the comment is on disk, [CasResult.RACED] if [acceptMove] rejected it (the tmp is discarded), or
     * [CasResult.FAILED] on an IO error. Never leaks a stray `.json.tmp`. The create/upsert path passes an
     * unconditional predicate; the fenced path ([casWrite]) re-reads the on-disk generation there.
     */
    private fun stagedWrite(uuid: String, json: String, acceptMove: () -> Boolean): CasResult {
        return try {
            Files.createDirectories(commentsDir)
            val tmp = Files.createTempFile(commentsDir, uuid, ".json.tmp")
            try {
                Files.writeString(tmp, json)
                interposeBeforeMoveForTest?.invoke()
                if (!acceptMove()) {
                    Files.deleteIfExists(tmp) // predicate rejected the move — don't clobber; don't leak the tmp
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

    /**
     * Create-or-replace write for the create/upsert path ([save]). Returns true once the comment is on disk —
     * the caller records it persisted only then, so a failed write keeps the in-memory record without the
     * watcher later treating the missing file as a delete. Fenced reply mutations use [casWrite] instead.
     */
    private fun writeAtomically(uuid: String, json: String): Boolean =
        stagedWrite(uuid, json) { true } == CasResult.WROTE

    /**
     * Write [json] over `comments/<uuid>.json`, but only if the on-disk generation still equals
     * [expectedGeneration] (null means "expect the file ABSENT" — a resurrecting revive). The generation is
     * re-read as close to the atomic move as possible; a mismatch — a peer wrote a newer generation, a hook
     * claimed the file into `processed/` (now absent), or a foreign writer left it unreadable — returns
     * [CasResult.RACED] without writing, so the caller re-reads and re-merges. The residual window between
     * this re-check and the move is the accepted optimistic-CAS sliver (plan.html §5 "generation fence").
     */
    private fun casWrite(uuid: String, json: String, expectedGeneration: Int?): CasResult =
        stagedWrite(uuid, json) { matchesGeneration(readDisk(uuid), expectedGeneration) }

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
        } catch (e: java.nio.file.NoSuchFileException) {
            // The file vanished between the exists-check and the read — the exact, benign event the fence
            // targets (a hook moving it into processed/, or a peer delete). Classify as Absent, not Unreadable,
            // so the caller takes the clean consume/delete path rather than a spurious "corrupt file" retry.
            DiskRead.Absent
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
        // in-memory state, which the next mutation retries. `internal` so the give-up test asserts the exact bound.
        internal const val MAX_CAS_ATTEMPTS = 4

        // Single-threaded so disk writes/deletes for the same comment can't race. The platform
        // requires a capitalized pool name (BoundedTaskExecutor asserts this).
        private val IO_POOL by lazy {
            AppExecutorUtil.createBoundedApplicationPoolExecutor("Heview comment IO", 1)
        }
    }
}
