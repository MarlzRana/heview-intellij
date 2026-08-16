package com.marlzrana.heview.storage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.marlzrana.heview.model.CommentJson
import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.model.HeviewComment
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * In-memory index + JSON persistence for comment threads, mirroring reviewa's `CommentStore`:
 * one file per thread at `<commentsDir>/<uuid>.json`, with the in-memory map as the source of truth.
 *
 * Threading: the index and listeners are touched only on the EDT. Disk I/O is offloaded to a serial
 * background executor ([runIo]) so the EDT never blocks (the platform asserts against I/O on the
 * EDT), and results are marshalled back with [runOnEdt]. Tests inject synchronous executors, so the
 * store behaves deterministically without an IDE.
 */
class CommentStore(
    private val commentsDir: Path,
    private val runIo: (Runnable) -> Unit = { IO_POOL.execute(it) },
    private val runOnEdt: (Runnable) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
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

    /**
     * Upsert. Updates the index and fires listeners immediately (EDT), then persists atomically on a
     * background thread. A persist failure is logged; the in-memory record stays.
     */
    fun save(comment: HeviewComment) {
        index[comment.uuid] = comment
        fireChanged()
        val uuid = comment.uuid
        val json = CommentJson.encode(comment)
        runIo {
            if (!writeAtomically(uuid, json)) {
                runOnEdt { LOG.warn("heview: failed to persist comment $uuid") }
            }
        }
    }

    fun get(uuid: String): HeviewComment? = index[uuid]

    fun all(): List<HeviewComment> = index.values.toList()

    fun pendingCount(): Int = index.values.count { it.status == CommentStatus.PENDING }

    fun processedCount(): Int = index.values.count { it.status == CommentStatus.PROCESSED }

    /**
     * UI-initiated removal. Deletes the on-disk file on a background thread first; only on success
     * does it drop the record and fire listeners (EDT). A real deletion failure keeps the comment
     * visible rather than pretending it is gone while the file lingers in the shared pool. (A file
     * that is already absent — e.g. consumed by a hook — counts as success.)
     */
    fun delete(uuid: String) {
        if (!index.containsKey(uuid)) return
        runIo {
            val deleted = try {
                Files.deleteIfExists(fileFor(uuid))
                true
            } catch (e: IOException) {
                false
            }
            runOnEdt {
                if (deleted) {
                    if (index.remove(uuid) != null) fireChanged()
                } else {
                    LOG.warn("heview: failed to delete comment $uuid; keeping it visible")
                }
            }
        }
    }

    /** Write to a temp file in the same directory, then atomically move it over the target. */
    private fun writeAtomically(uuid: String, json: String): Boolean = try {
        Files.createDirectories(commentsDir)
        val tmp = Files.createTempFile(commentsDir, uuid, ".json.tmp")
        Files.writeString(tmp, json)
        try {
            Files.move(tmp, fileFor(uuid), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, fileFor(uuid), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (e: IOException) {
        false
    }

    companion object {
        private val LOG = logger<CommentStore>()

        // Single-threaded so disk writes/deletes for the same comment can't race.
        private val IO_POOL by lazy {
            AppExecutorUtil.createBoundedApplicationPoolExecutor("heview-comment-io", 1)
        }
    }
}
