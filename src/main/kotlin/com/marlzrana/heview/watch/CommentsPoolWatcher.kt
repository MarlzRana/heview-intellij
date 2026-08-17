package com.marlzrana.heview.watch

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.marlzrana.heview.model.CommentStatus
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
 * A [reconcile] pass — run once startup [CommentStore.hydrate] applies (via [CommentStore.whenHydrated],
 * a one-shot, so we don't re-scan on every store change) and again on OVERFLOW — marks *Seen* any
 * in-index PENDING comment whose file is already in `processed/`, covering a consume that raced hydrate
 * or a dropped watch event. It only ever marks Seen, never evicts.
 *
 * Threading: a single daemon thread owns the blocking [WatchService] and rebuilds it if the watched dir
 * is removed at runtime; every store mutation hops to the EDT via [runEdt] and is dropped once this
 * service is [dispose]d. Directory scans (sweep/reconcile stat) run off the EDT. All inputs are
 * injectable so the classification + filesystem effects unit-test against temp dirs with synchronous
 * dispatch; the live [WatchService] loop is dogfooded in `runIde`.
 *
 * Scope note: this is the consumption slice + the shared multi-client tombstone contract (consume =
 * atomic move; Seen = tombstone present for an in-index uuid; delete = vanished with no tombstone;
 * retention = age-based sweep). Full CREATE/MODIFY sync between live clients is a later increment —
 * see plan.html §5 "Multi-client sync".
 */
@Service(Service.Level.APP)
internal class CommentsPoolWatcher(
    private val commentsDir: Path,
    private val storeProvider: () -> CommentStore,
    private val runEdt: (Runnable) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
    private val runIo: (Runnable) -> Unit = { AppExecutorUtil.getAppExecutorService().execute(it) },
) : Disposable {
    /**
     * Application-service constructor. Light services must not resolve their dependencies in the
     * constructor, so [CommentStore] is fetched lazily (on first use, off-EDT) via the provider.
     */
    @Suppress("unused")
    constructor() : this(HeviewPaths.commentsDir, { service() })

    /** Test constructor: a temp-dir pool + an explicit store, with synchronous EDT/IO dispatch. */
    @TestOnly
    constructor(commentsDir: Path, store: CommentStore) :
        this(commentsDir, { store }, { it.run() }, { it.run() })

    private val store: CommentStore get() = storeProvider()

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
        // One-shot catch-up once startup hydrate applies (and again on OVERFLOW): re-derive Seen for a
        // consume that raced hydrate. whenHydrated is EDT-confined and fires at most once — no per-change
        // listener to leak across the watch-rebuild loop below.
        runEdt { if (!disposed) store.whenHydrated { reconcile() } }

        while (!disposed) {
            val ws = openWatch()
            if (ws == null) {
                if (!sleepBeforeRetry()) return // interrupted → disposed
                continue
            }
            watchService = ws
            // Catch up any consume that landed BEFORE this (re)registration: the whenHydrated pass can
            // run before register(), and a runtime watch-rebuild would otherwise miss consumes during the
            // outage. markProcessed-only, so it's safe to repeat on every (re)register.
            reconcile()
            try {
                watchLoop(ws)
            } catch (e: ClosedWatchServiceException) {
                return // disposed (watchService.close())
            } catch (e: InterruptedException) {
                return // disposed (thread.interrupt())
            } finally {
                closeQuietly(ws)
                watchService = null
            }
            // watchLoop returned because the watch key went invalid (the dir was removed/replaced at
            // runtime), not a dispose — rebuild the watch after a short delay so Seen/evict keeps working
            // without needing a project reopen or an IDE restart.
            if (!sleepBeforeRetry()) return
        }
    }

    /**
     * Create the pool dir, reclaim aged tombstones, and register a fresh watch — or null on failure.
     * Catches broadly (not just IOException): some filesystems throw UnsupportedOperationException from
     * newWatchService()/register(), or a SecurityException from createDirectories(); those must degrade
     * to a retry, not escape and kill the daemon for the whole session.
     */
    private fun openWatch(): WatchService? {
        try {
            Files.createDirectories(commentsDir)
        } catch (e: Exception) {
            LOG.warn("heview: cannot create the comments dir to watch $commentsDir", e)
            return null
        }
        // Reclaim tombstones older than the retention window at startup and on each re-register. NOT per
        // event: an O(dir) sweep on every ENTRY_DELETE would block the watch loop and drop events (→
        // OVERFLOW) under bulk consumption.
        sweepProcessed()
        val ws = try {
            FileSystems.getDefault().newWatchService()
        } catch (e: Exception) {
            LOG.warn("heview: cannot create a watch service for $commentsDir", e)
            return null
        }
        return try {
            commentsDir.register(ws, StandardWatchEventKinds.ENTRY_DELETE)
            ws
        } catch (e: Exception) {
            LOG.warn("heview: cannot watch the comments dir $commentsDir", e)
            closeQuietly(ws) // register failed — don't leak the native watcher / file descriptors
            null
        }
    }

    private fun watchLoop(ws: WatchService) {
        while (true) {
            val key = ws.take() // blocks; throws ClosedWatchServiceException when disposed
            for (event in key.pollEvents()) {
                // OVERFLOW means events were dropped — reconcile to re-derive Seen for any comment that
                // was consumed while we weren't looking.
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    reconcile()
                    continue
                }
                // For a directory watch the context is the entry's relative name (a single component).
                val name = event.context() as? Path ?: continue
                val uuid = uuidOfDeletedEntry(name.toString()) ?: continue
                onCommentFileDeleted(uuid)
            }
            if (!key.reset()) break // the watched dir is gone/inaccessible — leave the loop to rebuild
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
    }

    private fun markProcessed(uuid: String) = runEdt { if (!disposed) store.markProcessed(uuid) }

    private fun evict(uuid: String) = runEdt { if (!disposed) store.evict(uuid) }

    /**
     * Catch-up for the event-only design: an agent consume that raced startup [CommentStore.hydrate]
     * (its `ENTRY_DELETE` fired before the record was in the index), or a dropped/OVERFLOW watch event,
     * leaves an in-index PENDING comment whose file is already in `processed/`. Mark those *Seen*.
     *
     * Only ever marks Seen — never evicts — so it can't race the async write of a freshly-saved comment:
     * a just-created comment has no `processed/` tombstone yet, so it is left untouched. Idempotent and
     * convergent (a marked comment is no longer PENDING, so a re-entrant pass skips it). Snapshot on the
     * EDT, stat off the EDT, apply on the EDT. Visible for direct unit testing.
     */
    fun reconcile() {
        runEdt {
            if (disposed) return@runEdt
            val pending = store.all().filter { it.status == CommentStatus.PENDING }.map { it.uuid }
            if (pending.isEmpty()) return@runEdt
            runIo {
                if (disposed) return@runIo
                val consumed = pending.filter { uuid ->
                    Files.notExists(commentsDir.resolve("$uuid.json")) &&
                        Files.exists(processedDir.resolve("$uuid.json"))
                }
                if (consumed.isEmpty()) return@runIo
                runEdt { if (!disposed) consumed.forEach { store.markProcessed(it) } }
            }
        }
    }

    /**
     * Reclaim consumed tombstones older than [TOMBSTONE_RETENTION]; recent ones are kept so every live
     * client can read the consumption signal and a consumed comment stays restorable. Bounds the growth
     * of processed/. Visible for direct unit testing.
     */
    fun sweepProcessed() {
        // Never follow a symlinked processed/: a planted `processed -> ~/.codex` (or similar) symlink
        // would make the age-sweep delete *.json files OUTSIDE heview. Require a real directory.
        if (Files.isSymbolicLink(processedDir) || !Files.isDirectory(processedDir)) return
        val cutoff = Instant.now().minus(TOMBSTONE_RETENTION)
        sweepAged("*.json", cutoff) // consumed tombstones
        sweepAged("*.json.tmp", cutoff) // crash-orphaned atomic-rewrite temps (see the injector claim())
    }

    private fun sweepAged(glob: String, cutoff: Instant) {
        try {
            Files.newDirectoryStream(processedDir, glob).use { stream ->
                stream.forEach { path ->
                    val mtime = try {
                        Files.getLastModifiedTime(path).toInstant()
                    } catch (e: IOException) {
                        return@forEach // can't stat it — leave it rather than risk dropping a fresh signal
                    }
                    if (mtime.isBefore(cutoff)) deleteQuietly(path)
                }
            }
        } catch (e: IOException) {
            LOG.warn("heview: failed to sweep $glob in $processedDir", e)
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            LOG.warn("heview: failed to delete $path", e)
        }
    }

    /** Sleep between watch (re)build attempts; false if interrupted (disposed) so run() can exit. */
    private fun sleepBeforeRetry(): Boolean =
        try {
            Thread.sleep(RETRY_DELAY_MS)
            !disposed
        } catch (e: InterruptedException) {
            false
        }

    private fun closeQuietly(ws: WatchService) {
        try {
            ws.close()
        } catch (e: IOException) {
            // ignore — already shutting down
        }
    }

    override fun dispose() {
        disposed = true // queued EDT hops become no-ops
        thread?.interrupt()
        watchService?.let { closeQuietly(it) }
    }

    companion object {
        private val LOG = logger<CommentsPoolWatcher>()

        // A consumed tombstone lingers this long so every live client (and a future "restore" action)
        // can read it; the sweep reclaims older ones. Not eager-deleted (that starves peers).
        private val TOMBSTONE_RETENTION: Duration = Duration.ofDays(14)

        // Backoff before rebuilding the watch after the watched dir was removed/replaced at runtime.
        private const val RETRY_DELAY_MS = 5_000L

        /**
         * The uuid a `comments/` `ENTRY_DELETE` refers to, or null to ignore the entry — a non-`.json`
         * name (notably the `processed/` subdirectory itself). Pure so the watch-loop parsing is testable.
         */
        fun uuidOfDeletedEntry(fileName: String): String? =
            if (fileName.endsWith(".json")) fileName.removeSuffix(".json") else null
    }
}
