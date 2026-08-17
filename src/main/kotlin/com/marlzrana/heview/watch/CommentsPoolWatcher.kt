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
 * An in-place atomic replace (tmp→rename over an existing pool file) also surfaces as an `ENTRY_DELETE`;
 * [onCommentFileDeleted] ignores the event when the pool file is still present, so a peer update can't
 * spuriously evict a live comment.
 *
 * Threading: a single daemon thread owns the blocking [WatchService]; every store mutation hops to the
 * EDT via [runEdt] (the store is EDT-confined) and is dropped once this service is [dispose]d. Sweep
 * deletes run on the watch thread (already off-EDT). All inputs are injectable so the classification +
 * filesystem effects unit-test against temp dirs with synchronous dispatch; the live [WatchService]
 * glue is dogfooded in `runIde`.
 *
 * Scope note: this is the consumption slice + the shared multi-client tombstone contract (consume =
 * atomic move; Seen = tombstone present for an in-index uuid; delete = vanished with no tombstone;
 * retention = age-based sweep). A consumption that races startup [CommentStore.hydrate] (or a dropped /
 * OVERFLOW event) has no catch-up yet; full CREATE/MODIFY sync + reconcile are a later increment —
 * see plan.html §5 "Multi-client sync".
 */
@Service(Service.Level.APP)
internal class CommentsPoolWatcher(
    private val commentsDir: Path,
    private val store: CommentStore,
    private val runEdt: (Runnable) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
) : Disposable {
    /** Application-service constructor — binds to the shared pool + the shared [CommentStore]. */
    @Suppress("unused")
    constructor() : this(HeviewPaths.commentsDir, service())

    /** Test constructor: a temp-dir pool + an explicit store, with synchronous EDT dispatch. */
    @TestOnly
    constructor(commentsDir: Path, store: CommentStore) : this(commentsDir, store, { it.run() })

    // Where a consumed comment is claimed to; derived so it can never be mis-paired with commentsDir.
    private val processedDir: Path = commentsDir.resolve("processed")

    private val started = AtomicBoolean(false)

    @Volatile
    private var disposed = false

    @Volatile
    private var watchService: WatchService? = null

    @Volatile
    private var thread: Thread? = null

    /** Start watching the pool, once per application; idempotent and safe to call off-EDT. */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        // Publish the thread reference BEFORE start(), so a dispose() racing startup can interrupt it
        // and close the service instead of leaking a daemon that outlives the plugin.
        val t = Thread({ run() }, "Heview comments watcher").apply { isDaemon = true }
        thread = t
        t.start()
    }

    private fun run() {
        if (disposed) return
        try {
            Files.createDirectories(commentsDir)
        } catch (e: IOException) {
            LOG.warn("heview: cannot create the comments dir to watch $commentsDir", e)
            started.set(false) // transient — let the next project-open retry (mirrors the hook installer)
            return
        }
        // Reclaim tombstones older than the retention window so processed/ can't grow without bound,
        // while leaving recent ones for live peers + a future restore action.
        sweepProcessed()

        val ws = try {
            FileSystems.getDefault().newWatchService()
        } catch (e: IOException) {
            LOG.warn("heview: cannot create a watch service for $commentsDir", e)
            started.set(false)
            return
        }
        try {
            commentsDir.register(ws, StandardWatchEventKinds.ENTRY_DELETE)
        } catch (e: IOException) {
            LOG.warn("heview: cannot watch the comments dir $commentsDir", e)
            ws.close() // register failed — don't leak the native watcher / file descriptors
            started.set(false)
            return
        }
        if (disposed) {
            ws.close()
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
        // The loop exited because the watch key went invalid (the dir was removed), not a dispose:
        // re-arm so a later ensureStarted() can rebuild the watch.
        if (!disposed) started.set(false)
    }

    private fun watchLoop(ws: WatchService) {
        while (true) {
            val key = ws.take() // blocks; throws ClosedWatchServiceException when disposed
            for (event in key.pollEvents()) {
                // OVERFLOW means events were dropped; without a reconcile pass we can't recover the
                // lost signal here (tracked as a known gap — see the class scope note).
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue
                // For a directory watch the context is the entry's relative name (a single component).
                val name = event.context() as? Path ?: continue
                val uuid = uuidOfDeletedEntry(name.toString()) ?: continue
                onCommentFileDeleted(uuid)
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
        // An atomic in-place replace (tmp→rename over an existing file) also fires ENTRY_DELETE. If the
        // pool file is still there it wasn't really removed — ignore, so a peer update / a re-save can't
        // spuriously evict a live comment.
        if (Files.exists(commentsDir.resolve("$uuid.json"))) return

        val tombstone = processedDir.resolve("$uuid.json")
        when {
            // Confirmed consumed → Seen.
            Files.exists(tombstone) -> markProcessed(uuid)
            // Confirmed gone with no tombstone → a peer/user delete.
            Files.notExists(tombstone) -> evict(uuid)
            // Neither exists nor notExists is true → the tombstone's presence couldn't be determined
            // (an access/I/O error). Don't destroy in-memory state on a failed check; leave it as-is.
            else -> LOG.warn("heview: could not classify $uuid (processed/ check indeterminate)")
        }

        // Bound processed/ within a long-lived session, not just at the next startup.
        sweepProcessed()
    }

    private fun markProcessed(uuid: String) = runEdt { if (!disposed) store.markProcessed(uuid) }

    private fun evict(uuid: String) = runEdt { if (!disposed) store.evict(uuid) }

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
        disposed = true // queued EDT hops become no-ops
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

        /**
         * The uuid a `comments/` `ENTRY_DELETE` refers to, or null to ignore the entry — a non-`.json`
         * name (notably the `processed/` subdirectory itself). Pure so the watch-loop parsing is testable.
         */
        fun uuidOfDeletedEntry(fileName: String): String? =
            if (fileName.endsWith(".json")) fileName.removeSuffix(".json") else null
    }
}
