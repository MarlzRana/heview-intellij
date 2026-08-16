package com.marlzrana.heview.watch

import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.sampleComment
import com.marlzrana.heview.storage.CommentStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Deterministic coverage for the consumption watcher's classification + filesystem effects, driven by
 * calling [CommentsPoolWatcher.onCommentFileDeleted] / [CommentsPoolWatcher.sweepConsumed] directly
 * against temp dirs and a synchronous store — no live [java.nio.file.WatchService] (that glue is thin
 * and dogfooded in `runIde`). Uses the test constructor, which runs EDT dispatch inline.
 */
class CommentsPoolWatcherTest {
    // Synchronous executors so the store's async disk + EDT hops run inline and deterministically.
    private fun store(dir: Path) = CommentStore(dir, runIo = { it.run() }, runEdt = { it.run() })

    private fun watcher(comments: Path, store: CommentStore) =
        CommentsPoolWatcher(comments, comments.resolve("consumed"), store)

    private fun seedConsumedTombstone(comments: Path, uuid: String) {
        val consumed = Files.createDirectories(comments.resolve("consumed"))
        Files.writeString(consumed.resolve("$uuid.json"), "{}") // presence is the signal; content unused
    }

    @Test
    fun `a comment moved into consumed is marked Seen and its tombstone is reclaimed`(@TempDir dir: Path) {
        val comments = Files.createDirectories(dir.resolve("comments"))
        val store = store(comments)
        store.save(sampleComment(uuid = "u1")) // pending, present in the index
        // Simulate the hook's claim: the pool file has moved into consumed/.
        seedConsumedTombstone(comments, "u1")
        Files.deleteIfExists(comments.resolve("u1.json"))

        watcher(comments, store).onCommentFileDeleted("u1")

        assertEquals(CommentStatus.PROCESSED, store.get("u1")?.status) // Seen (still indexed)
        assertFalse(Files.exists(comments.resolve("consumed/u1.json"))) // retention: tombstone deleted
    }

    @Test
    fun `a comment that simply vanished is evicted`(@TempDir dir: Path) {
        val comments = Files.createDirectories(dir.resolve("comments"))
        val store = store(comments)
        store.save(sampleComment(uuid = "u1"))
        Files.deleteIfExists(comments.resolve("u1.json")) // peer/user delete: no consumed tombstone

        watcher(comments, store).onCommentFileDeleted("u1")

        assertNull(store.get("u1")) // gone, not marked Seen
    }

    @Test
    fun `observing a delete for an already-removed uuid is idempotent`(@TempDir dir: Path) {
        val comments = Files.createDirectories(dir.resolve("comments"))
        val store = store(comments)

        // Our own delete() already dropped it; the watcher later sees the same ENTRY_DELETE.
        watcher(comments, store).onCommentFileDeleted("gone")

        assertNull(store.get("gone")) // no crash, no resurrection
    }

    @Test
    fun `sweepConsumed deletes stale json tombstones and leaves other files alone`(@TempDir dir: Path) {
        val comments = Files.createDirectories(dir.resolve("comments"))
        val consumed = Files.createDirectories(comments.resolve("consumed"))
        Files.writeString(consumed.resolve("a.json"), "{}")
        Files.writeString(consumed.resolve("b.json"), "{}")
        Files.writeString(consumed.resolve("keep.txt"), "x")

        watcher(comments, store(comments)).sweepConsumed()

        assertFalse(Files.exists(consumed.resolve("a.json")))
        assertFalse(Files.exists(consumed.resolve("b.json")))
        assertTrue(Files.exists(consumed.resolve("keep.txt")))
    }

    @Test
    fun `sweepConsumed is a no-op when there is no consumed dir`(@TempDir dir: Path) {
        val comments = Files.createDirectories(dir.resolve("comments"))

        watcher(comments, store(comments)).sweepConsumed() // must not throw

        assertFalse(Files.exists(comments.resolve("consumed")))
    }
}
