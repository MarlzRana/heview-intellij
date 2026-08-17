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

    /** Upsert: index + fire immediately (EDT), then persist atomically on a background thread. */
    fun save(comment: HeviewComment) {
        index[comment.uuid] = comment
        fireChanged()
        val uuid = comment.uuid
        val json = CommentJson.encode(comment)
        runIo { if (writeAtomically(uuid, json)) persisted.add(uuid) }
    }

    /** True once [uuid] has been confirmed on disk (write succeeded or hydrated) and not since removed. */
    fun isPersisted(uuid: String): Boolean = persisted.contains(uuid)

    /**
     * The per-reply state machine (plan.html §5). Each mutates one thread's [HeviewComment.replies],
     * recomputes the thread's derived `content`/`status`/`created_at` ([recomputed]) and re-persists.
     * The target reply is identified **by value**, not by array index: the card renders indices but a
     * concurrent mutation (another split, or deleting an earlier reply mid-edit) can shift them, so the
     * store locates the actual reply with [List.indexOf] and no-ops if it is gone. The replies are
     * normalized first, so a foreign/legacy single-`content` file behaves as a one-reply thread. All are
     * no-ops on an unknown uuid or a reply no longer present; [addReply]/[editReply] also drop blank text.
     *
     * - [addReply] appends a new PENDING reply (the thread's "Leave a comment" box).
     * - [editReply] replaces [target]'s text and revives it to PENDING (editing a Seen reply makes it
     *   actionable again — reviewa parity).
     * - [rependReply] revives a Seen (PROCESSED) [target] to PENDING; a no-op if it is already actionable.
     * - [deleteReply] removes [target]: the last reply drops the whole thread; if only Seen replies
     *   remain the thread leaves the injectable pool but stays visible (reviewa parity). It does NOT bump
     *   `created_at` (a delete must not reorder the remaining replies' injection — reviewa parity).
     */
    fun addReply(uuid: String, text: String, author: String, now: String) {
        if (text.isBlank()) return
        val current = index[uuid] ?: return
        val replies = current.normalizedReplies(author) + HeviewReply(text, CommentStatus.PENDING, author, now)
        revive(current.copy(replies = replies).recomputed(now))
    }

    fun editReply(uuid: String, target: HeviewReply, newText: String, now: String) {
        if (newText.isBlank()) return
        val current = index[uuid] ?: return
        val replies = current.normalizedReplies(defaultAuthor()).toMutableList()
        val at = replies.indexOf(target)
        if (at < 0) return
        replies[at] = target.copy(content = newText, status = CommentStatus.PENDING, createdAt = now)
        revive(current.copy(replies = replies).recomputed(now))
    }

    fun rependReply(uuid: String, target: HeviewReply, now: String) {
        if (target.status != CommentStatus.PROCESSED) return
        val current = index[uuid] ?: return
        val replies = current.normalizedReplies(defaultAuthor()).toMutableList()
        val at = replies.indexOf(target)
        if (at < 0) return
        replies[at] = target.copy(status = CommentStatus.PENDING, createdAt = now)
        revive(current.copy(replies = replies).recomputed(now))
    }

    fun deleteReply(uuid: String, target: HeviewReply) {
        val current = index[uuid] ?: return
        val replies = current.normalizedReplies(defaultAuthor()).toMutableList()
        if (!replies.remove(target)) return
        if (replies.isEmpty()) {
            delete(uuid) // the last reply is gone → drop the whole thread + its pool file
            return
        }
        // Preserve created_at — deleting a reply must not reorder the thread's injection (reviewa parity).
        val updated = current.copy(replies = replies).recomputed(current.createdAt)
        if (updated.status == CommentStatus.PENDING) {
            revive(updated) // still actionable → keep it in the injectable pool
        } else {
            retainSeenOffPool(updated) // only Seen replies remain (reviewa's deleteComment-no-actionable)
        }
    }

    /**
     * Persist a revived (PENDING) thread into the pool. The consumption tombstone is removed first: a
     * tombstone in `processed/` for an in-index uuid is exactly the watcher's "Seen" signal, so leaving
     * it would let the next catch-up reconcile flip this thread straight back to PROCESSED. The delete
     * is enqueued on the serial IO executor before [save]'s write, so they run in order; a no-op when
     * there was no tombstone (a mutation on an already-actionable thread).
     */
    private fun revive(updated: HeviewComment) {
        clearTombstone(updated.uuid)
        save(updated)
    }

    /**
     * A thread whose last actionable reply was deleted: keep its (all-Seen) replies visible in the UI
     * but drop it from the injectable pool — reviewa's `deleteComment`-with-no-actionable behaviour. No
     * tombstone is written (unlike a hook consume), so it is not restored after a restart. `persisted`
     * is cleared *before* the unlink so the watcher's delete-event handler (also gated on `isPersisted`)
     * classifies the vanish as our own retain, not a peer delete, and leaves the Seen thread in the UI.
     */
    private fun retainSeenOffPool(updated: HeviewComment) {
        index[updated.uuid] = updated
        persisted.remove(updated.uuid)
        fireChanged()
        runIo {
            try {
                Files.deleteIfExists(fileFor(updated.uuid))
            } catch (e: IOException) {
                LOG.warn("heview: failed to unlink the now-Seen comment file ${updated.uuid}", e)
            }
        }
    }

    private fun clearTombstone(uuid: String) {
        runIo {
            try {
                // Never follow a symlinked processed/ (matches the injector + sweep guards): a planted
                // `processed -> ~/.codex` link would make this delete a file OUTSIDE ~/.heview.
                if (Files.isSymbolicLink(processedDir)) return@runIo
                Files.deleteIfExists(processedDir.resolve("$uuid.json"))
            } catch (e: IOException) {
                LOG.warn("heview: failed to remove the consumption tombstone for $uuid", e)
            }
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
        if (comment.status == CommentStatus.PROCESSED) {
            // plan.html §5: processed comments are not persisted/actionable — never hydrate one as pending.
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
        // Gson (via Unsafe) can leave a null element in a non-null-typed List, so null-check each.
        @Suppress("SENSELESS_COMPARISON")
        val valid = c.replies?.filter { it != null && isWellFormedReply(it) }.orEmpty()
        if (valid.isNotEmpty()) return valid
        return listOf(HeviewReply(c.content, c.status, defaultAuthor(), c.createdAt))
    }

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

    /** Drop the record and fire immediately; unlink the file best-effort in the background. */
    fun delete(uuid: String) {
        if (index.remove(uuid) == null) return
        persisted.remove(uuid)
        fireChanged()
        runIo {
            try {
                Files.deleteIfExists(fileFor(uuid))
            } catch (e: IOException) {
                LOG.warn("heview: failed to delete comment file $uuid", e)
            }
        }
    }

    /**
     * Write to a temp file in the same directory, then atomically move it over the target. Returns true
     * if the comment is now on disk — the caller records it as persisted only then, so a failed write
     * keeps the in-memory record without the watcher later treating the missing file as a delete.
     */
    private fun writeAtomically(uuid: String, json: String): Boolean {
        return try {
            Files.createDirectories(commentsDir)
            val tmp = Files.createTempFile(commentsDir, uuid, ".json.tmp")
            try {
                Files.writeString(tmp, json)
                try {
                    Files.move(tmp, fileFor(uuid), StandardCopyOption.ATOMIC_MOVE)
                } catch (e: IOException) {
                    // ATOMIC_MOVE unsupported, or the target exists on a non-POSIX FS → non-atomic
                    // replace. On the POSIX targets we support (macOS/Linux) the atomic move already
                    // replaces, so this fallback is only reached off-platform.
                    Files.move(tmp, fileFor(uuid), StandardCopyOption.REPLACE_EXISTING)
                }
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

    /** Normalize for path comparison; fall back to the raw string if it isn't a valid path. */
    private fun normalizePath(path: String): String =
        try {
            Path.of(path).normalize().toString()
        } catch (e: InvalidPathException) {
            path
        }

    companion object {
        private val LOG = logger<CommentStore>()

        // Single-threaded so disk writes/deletes for the same comment can't race. The platform
        // requires a capitalized pool name (BoundedTaskExecutor asserts this).
        private val IO_POOL by lazy {
            AppExecutorUtil.createBoundedApplicationPoolExecutor("Heview comment IO", 1)
        }
    }
}
