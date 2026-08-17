package com.marlzrana.heview.watch

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.marlzrana.heview.storage.CommentStore
import com.marlzrana.heview.storage.HeviewPaths
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches the shared comment pool for files leaving it and reconciles the in-memory [CommentStore],
 * delivering the "mark Seen" step of the lifecycle (plan.html §5). A durable pool doesn't self-clear
 * the way reviewa's session purge did, so this is what tells a live card its comment was picked up.
 *
 * A disappearance from `comments/` has two causes that a bare `unlink` can't tell apart, so the
 * injector hooks encode the intent by **moving** a consumed file into `comments/processed/` instead of
 * unlinking it (see the bundled scripts). The move is an atomic rename, so whenever the pending file's
 * `ENTRY_DELETE` is observed the tombstone already exists. This watcher reads that signal:
 * - the file now exists in `processed/`  → an agent hook consumed it → [CommentStore.markProcessed]
 *   flips the thread to *Seen*. The tombstone is **left in place** — it is the consumed comment itself
 *   (so a future "restore" action can revive it) and every other live client (e.g. heview-vscode) must
 *   observe it to reach the same verdict; an eager per-client delete would starve a slower peer of the
 *   signal. Tombstones are reclaimed by age instead ([sweepProcessed], [TOMBSTONE_RETENTION]).
 * - the file simply vanished           → a peer/user delete (or our own [CommentStore.delete] already
 *   applied) → [CommentStore.evict] drops it. Both store calls are idempotent, so a self-delete the
 *   watcher later observes is a no-op — no suppression set is needed (the `processed/` move replaces it).
 *
 * Threading: a single daemon thread owns the blocking [WatchService]; every store mutation hops to the
 * EDT via [runEdt] (the store is EDT-confined). Sweep deletes run on the watch thread (already off-EDT).
 * All inputs are injectable so the classification + filesystem effects unit-test against temp dirs with
 * synchronous dispatch; the live [WatchService] glue is dogfooded in `runIde`.
 *
 * Scope note: this is the consumption slice + the shared multi-client tombstone contract (consume =
 * atomic move; Seen = tombstone present for an in-index uuid; delete = vanished with no tombstone;
 * retention = age-based sweep). Full CREATE/MODIFY sync between live clients is a later increment —
 * see plan.html §5 "Multi-client sync".
 */
@Service(Service.Level.APP)
internal class CommentsPoolWatcher(
    private val commentsDir: Path,
    private val processedDir: Path,
    private val storeProvider: () -> CommentStore,
    private val runEdt: (Runnable) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
) : Disposable {
    /** Application-service constructor — binds to the shared pool + the shared [CommentStore]. */
    @Suppress("unused")
    constructor() : this(HeviewPaths.commentsDir, HeviewPaths.processedDir, { service() })

    /** Test constructor: a temp-dir pool, an explicit store, and synchronous EDT dispatch. */
    @TestOnly
    constructor(commentsDir: Path, processedDir: Path, store: CommentStore) :
        this(commentsDir, processedDir, { store }, { it.run() })

    private val store: CommentStore get() = storeProvider()

    private val started = AtomicBoolean(false)

    @Volatile
    private var watchService: WatchService? = null

    @Volatile
    private var thread: Thread? = null

    /** Start watching the pool, once per application; idempotent and safe to call off-EDT. */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        thread = Thread({ run() }, "Heview comments watcher").apply {
            isDaemon = true
            start()
        }
    }

    private fun run() {
        try {
            Files.createDirectories(commentsDir)
        } catch (e: IOException) {
            LOG.warn("heview: cannot create the comments dir to watch $commentsDir", e)
            return
        }
        // Reclaim tombstones older than the retention window so processed/ can't grow without bound,
        // while leaving recent ones for live peers + a future restore action.
        sweepProcessed()
        val ws = try {
            FileSystems.getDefault().newWatchService().also {
                commentsDir.register(it, StandardWatchEventKinds.ENTRY_DELETE)
            }
        } catch (e: IOException) {
            LOG.warn("heview: cannot watch the comments dir $commentsDir", e)
            return
        }
        watchService = ws
        try {
            watchLoop(ws)
        } catch (e: ClosedWatchServiceException) {
            // disposed (watchService.close()) — normal shutdown.
        } catch (e: InterruptedException) {
            // disposed (thread.interrupt()) — normal shutdown.
        } finally {
            try {
                ws.close()
            } catch (e: IOException) {
                // ignore — already shutting down
            }
        }
    }

    private fun watchLoop(ws: WatchService) {
        while (true) {
            val key = ws.take() // blocks; throws ClosedWatchServiceException when disposed
            for (event in key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue
                // For a directory watch the context is the entry's relative name (a single component),
                // so there is no path-separator to worry about when resolving into processed/.
                val name = event.context() as? Path ?: continue
                val fileName = name.toString()
                if (!fileName.endsWith(".json")) continue // skips the processed/ subdir entry itself
                onCommentFileDeleted(fileName.removeSuffix(".json"))
            }
            if (!key.reset()) break // the watched dir is gone/inaccessible — stop the loop
        }
    }

    /**
     * Classify a `<uuid>.json` that left `comments/`: a matching file in `processed/` means an agent
     * hook consumed it (→ *Seen*, tombstone left for peers + age-sweep), otherwise it was deleted
     * (→ evict). Invoked on the watch thread; store mutations hop to the EDT. Visible for unit testing.
     */
    fun onCommentFileDeleted(uuid: String) {
        val tombstone = processedDir.resolve("$uuid.json")
        if (Files.exists(tombstone)) {
            // Consumed: mark Seen, but LEAVE the tombstone — other live clients each need to observe it,
            // and it is the consumed comment itself (a future restore reads it back). Age-swept, never
            // eager-deleted (an eager delete would starve a slower peer of the signal).
            runEdt { store.markProcessed(uuid) }
        } else {
            runEdt { store.evict(uuid) }
        }
    }

    /**
     * Reclaim consumed tombstones older than [TOMBSTONE_RETENTION]; recent ones are kept so every live
     * client can read the consumption signal and a consumed comment stays restorable. Bounds the growth
     * of processed/. Visible for direct unit testing.
     */
    fun sweepProcessed() {
        if (!Files.isDirectory(processedDir)) return
        val cutoff = Instant.now().minus(TOMBSTONE_RETENTION)
        try {
            Files.newDirectoryStream(processedDir, "*.json").use { stream ->
                stream.forEach { path ->
                    val mtime = try {
                        Files.getLastModifiedTime(path).toInstant()
                    } catch (e: IOException) {
                        return@forEach // can't stat it — leave it rather than risk dropping a fresh peer signal
                    }
                    if (mtime.isBefore(cutoff)) deleteQuietly(path)
                }
            }
        } catch (e: IOException) {
            LOG.warn("heview: failed to sweep the processed dir $processedDir", e)
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            LOG.warn("heview: failed to delete $path", e)
        }
    }

    override fun dispose() {
        thread?.interrupt()
        try {
            watchService?.close()
        } catch (e: IOException) {
            // ignore — shutting down
        }
    }

    companion object {
        private val LOG = logger<CommentsPoolWatcher>()

        // A consumed tombstone lingers this long so every live client (and a future "restore" action)
        // can read it; the startup sweep reclaims older ones. Not eager-deleted (that starves peers).
        private val TOMBSTONE_RETENTION: Duration = Duration.ofDays(14)
    }
}
