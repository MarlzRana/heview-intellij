package com.marlzrana.heview.storage

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.marlzrana.heview.model.CommentJson
import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.model.HeviewComment
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
) {
    /** Application-service constructor — binds to the shared `~/.heview/comments` pool. */
    @Suppress("unused")
    constructor() : this(HeviewPaths.commentsDir)

    private val index = LinkedHashMap<String, HeviewComment>()
    private val listeners = mutableListOf<() -> Unit>()
    private val hydrated = AtomicBoolean(false)

    /** Register a change listener; dispose the returned handle to unregister (e.g. on project close). */
    fun addChangeListener(listener: () -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners.remove(listener) }
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
        runIo { writeAtomically(uuid, json) }
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
                }
                if (changed) fireChanged()
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
        return comment
    }

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
     * Flip a comment to [CommentStatus.PROCESSED] in memory (the "Seen" state) and fire — for the
     * consumption watcher, when an agent hook has consumed the file (moved it into `processed/`).
     *
     * Deliberately does NOT persist: a processed comment is not written to the pool (plan.html §5), and
     * its file has already left `comments/`, so re-saving here would resurrect an injectable duplicate.
     * No-op if the uuid is unknown or already processed.
     */
    fun markProcessed(uuid: String) {
        val current = index[uuid] ?: return
        if (current.status == CommentStatus.PROCESSED) return
        index[uuid] = current.copy(status = CommentStatus.PROCESSED)
        fireChanged()
    }

    /**
     * Drop a record from the index and fire, WITHOUT touching disk — for the watcher when a comment's
     * file simply vanished from the pool (a peer/user delete), where there is nothing left to unlink.
     * No-op if the uuid is unknown (so a self-[delete] the watcher later observes is idempotent).
     */
    fun evict(uuid: String) {
        if (index.remove(uuid) == null) return
        fireChanged()
    }

    /** Drop the record and fire immediately; unlink the file best-effort in the background. */
    fun delete(uuid: String) {
        if (index.remove(uuid) == null) return
        fireChanged()
        runIo {
            try {
                Files.deleteIfExists(fileFor(uuid))
            } catch (e: IOException) {
                LOG.warn("heview: failed to delete comment file $uuid", e)
            }
        }
    }

    /** Write to a temp file in the same directory, then atomically move it over the target. */
    private fun writeAtomically(uuid: String, json: String) {
        try {
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
            } catch (e: IOException) {
                Files.deleteIfExists(tmp) // don't leak a stray .json.tmp into the shared pool
                throw e
            }
        } catch (e: IOException) {
            LOG.warn("heview: failed to persist comment $uuid", e)
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
