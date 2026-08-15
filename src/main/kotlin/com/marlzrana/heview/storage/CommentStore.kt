package com.marlzrana.heview.storage

import com.marlzrana.heview.model.CommentJson
import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.model.HeviewComment
import java.nio.file.Files
import java.nio.file.Path

/**
 * In-memory index + JSON persistence for comment threads, mirroring reviewa's `CommentStore`:
 * one file per thread at `<commentsDir>/<uuid>.json`, with the in-memory map as the source of truth.
 *
 * Deliberately free of any IDE/editor dependency so it is unit-testable against a temp directory.
 * The editor/inlay binding and the shared-pool wiring (`~/.heview/comments`) live in higher layers.
 */
class CommentStore(private val commentsDir: Path) {
    private val index = LinkedHashMap<String, HeviewComment>()
    private val listeners = mutableListOf<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun fireChanged() = listeners.forEach { it() }

    private fun fileFor(uuid: String): Path = commentsDir.resolve("$uuid.json")

    /** Upsert: (re)write the JSON file and index the record. */
    fun save(comment: HeviewComment) {
        Files.createDirectories(commentsDir)
        Files.writeString(fileFor(comment.uuid), CommentJson.encode(comment))
        index[comment.uuid] = comment
        fireChanged()
    }

    fun get(uuid: String): HeviewComment? = index[uuid]

    fun all(): List<HeviewComment> = index.values.toList()

    fun pendingCount(): Int = index.values.count { it.status == CommentStatus.PENDING }

    fun processedCount(): Int = index.values.count { it.status == CommentStatus.PROCESSED }

    /** UI-initiated removal: drop the record and delete its on-disk file. */
    fun delete(uuid: String) {
        if (index.remove(uuid) == null) return
        runCatching { Files.deleteIfExists(fileFor(uuid)) }
        fireChanged()
    }
}
