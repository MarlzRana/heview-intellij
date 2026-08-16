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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches the shared comment pool for files leaving it and reconciles the in-memory [CommentStore],
 * delivering the "mark Seen" step of the lifecycle (plan.html §5). A durable pool doesn't self-clear
 * the way reviewa's session purge did, so this is what tells a live card its comment was picked up.
 *
 * A disappearance from `comments/` has two causes that a bare `unlink` can't tell apart, so the
 * injector hooks encode the intent by **moving** a consumed file into `comments/consumed/` instead of
 * unlinking it (see the bundled scripts). This watcher reads that signal on each `ENTRY_DELETE`:
 * - the file now exists in `consumed/`  → an agent hook consumed it → [CommentStore.markProcessed]
 *   flips the thread to *Seen* (and the tombstone is deleted — retention); vs
 * - the file simply vanished           → a peer/user delete (or our own [CommentStore.delete] already
 *   applied) → [CommentStore.evict] drops it. Both store calls are idempotent, so a self-delete the
 *   watcher later observes is a no-op — no suppression set is needed (the `consumed/` move replaces it).
 *
 * Threading: a single daemon thread owns the blocking [WatchService]; every store mutation hops to the
 * EDT via [runEdt] (the store is EDT-confined). Retention/​sweep deletes run on the watch thread (it is
 * already off-EDT). All inputs are injectable so the classification + filesystem effects unit-test
 * against temp dirs with synchronous dispatch; the live [WatchService] glue is dogfooded in `runIde`.
 *
 * Scope note: this is the consumption slice. The full multi-client pool watcher (CREATE/MODIFY, a
 * shared tombstone contract with heview-vscode) is a later increment — see plan.html §5 "Multi-client".
 */
@Service(Service.Level.APP)
internal class CommentsPoolWatcher(
    private val commentsDir: Path,
    private val consumedDir: Path,
    private val storeProvider: () -> CommentStore,
    private val runEdt: (Runnable) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
) : Disposable {
    /** Application-service constructor — binds to the shared pool + the shared [CommentStore]. */
    @Suppress("unused")
    constructor() : this(HeviewPaths.commentsDir, HeviewPaths.consumedDir, { service() })

    /** Test constructor: a temp-dir pool, an explicit store, and synchronous EDT dispatch. */
    @TestOnly
    constructor(commentsDir: Path, consumedDir: Path, store: CommentStore) :
        this(commentsDir, consumedDir, { store }, { it.run() })

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
        // Clear tombstones from consumption that happened while no IDE was running — those comments
        // never entered this session's index (their files left comments/ before hydrate), so there is
        // nothing to mark Seen; just keep consumed/ from growing unbounded.
        sweepConsumed()
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
                // so there is no path-separator to worry about when resolving into consumed/.
                val name = event.context() as? Path ?: continue
                val fileName = name.toString()
                if (!fileName.endsWith(".json")) continue // skips the consumed/ subdir entry itself
                onCommentFileDeleted(fileName.removeSuffix(".json"))
            }
            if (!key.reset()) break // the watched dir is gone/inaccessible — stop the loop
        }
    }

    /**
     * Classify a `<uuid>.json` that left `comments/`: a matching file in `consumed/` means an agent
     * hook consumed it (→ *Seen* + delete the tombstone), otherwise it was deleted (→ evict). Invoked
     * on the watch thread; store mutations hop to the EDT. Visible for direct unit testing.
     */
    fun onCommentFileDeleted(uuid: String) {
        val consumed = consumedDir.resolve("$uuid.json")
        if (Files.exists(consumed)) {
            runEdt { store.markProcessed(uuid) }
            deleteQuietly(consumed) // retention: the "consumed" signal has now been read
        } else {
            runEdt { store.evict(uuid) }
        }
    }

    /** Delete leftover `consumed/` tombstones. Visible for direct unit testing. */
    fun sweepConsumed() {
        if (!Files.isDirectory(consumedDir)) return
        try {
            Files.newDirectoryStream(consumedDir, "*.json").use { stream ->
                stream.forEach { deleteQuietly(it) }
            }
        } catch (e: IOException) {
            LOG.warn("heview: failed to sweep the consumed dir $consumedDir", e)
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
    }
}
