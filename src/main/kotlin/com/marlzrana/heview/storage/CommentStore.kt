package com.marlzrana.heview.storage

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.marlzrana.heview.model.CommentJson
import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.model.HeviewComment
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
) {
    /** Application-service constructor — binds to the shared `~/.heview/comments` pool. */
    @Suppress("unused")
    constructor() : this(HeviewPaths.commentsDir)

    private val index = LinkedHashMap<String, HeviewComment>()
    private val listeners = mutableListOf<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun fireChanged() = listeners.forEach { it() }

    private fun fileFor(uuid: String): Path = commentsDir.resolve("$uuid.json")

    /** Upsert: index + fire immediately (EDT), then persist atomically on a background thread. */
    fun save(comment: HeviewComment) {
        index[comment.uuid] = comment
        fireChanged()
        val uuid = comment.uuid
        val json = CommentJson.encode(comment)
        runIo { writeAtomically(uuid, json) }
    }

    fun get(uuid: String): HeviewComment? = index[uuid]

    fun all(): List<HeviewComment> = index.values.toList()

    fun pendingCount(): Int = index.values.count { it.status == CommentStatus.PENDING }

    fun processedCount(): Int = index.values.count { it.status == CommentStatus.PROCESSED }

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
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp, fileFor(uuid), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: FileAlreadyExistsException) {
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

    companion object {
        private val LOG = logger<CommentStore>()

        // Single-threaded so disk writes/deletes for the same comment can't race.
        private val IO_POOL by lazy {
            AppExecutorUtil.createBoundedApplicationPoolExecutor("heview-comment-io", 1)
        }
    }
}
