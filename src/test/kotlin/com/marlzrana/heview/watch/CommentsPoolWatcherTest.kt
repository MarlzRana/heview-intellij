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
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

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
    fun `a comment moved into consumed is marked Seen and its tombstone is left for peers`(@TempDir dir: Path) {
        val comments = Files.createDirectories(dir.resolve("comments"))
        val store = store(comments)
        store.save(sampleComment(uuid = "u1")) // pending, present in the index
        // Simulate the hook's claim: the pool file has moved into consumed/.
        seedConsumedTombstone(comments, "u1")
        Files.deleteIfExists(comments.resolve("u1.json"))

        watcher(comments, store).onCommentFileDeleted("u1")

        assertEquals(CommentStatus.PROCESSED, store.get("u1")?.status) // Seen (still indexed)
        // NOT eager-deleted: other live clients must observe the same tombstone (and it's the restore
        // source). It is reclaimed later by the age-based sweep, not here.
        assertTrue(Files.exists(comments.resolve("consumed/u1.json")))
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
    fun `sweepConsumed reclaims only tombstones older than the retention window`(@TempDir dir: Path) {
        val comments = Files.createDirectories(dir.resolve("comments"))
        val consumed = Files.createDirectories(comments.resolve("consumed"))
        Files.writeString(consumed.resolve("old.json"), "{}")
        Files.writeString(consumed.resolve("fresh.json"), "{}")
        Files.writeString(consumed.resolve("old.txt"), "x") // non-json ignored even when old
        val ancient = FileTime.from(Instant.now().minus(Duration.ofDays(20))) // past the 14-day window
        Files.setLastModifiedTime(consumed.resolve("old.json"), ancient)
        Files.setLastModifiedTime(consumed.resolve("old.txt"), ancient)

        watcher(comments, store(comments)).sweepConsumed()

        assertFalse(Files.exists(consumed.resolve("old.json"))) // aged out → reclaimed
        assertTrue(Files.exists(consumed.resolve("fresh.json"))) // recent → kept for peers/restore
        assertTrue(Files.exists(consumed.resolve("old.txt"))) // non-json left alone
    }

    @Test
    fun `sweepConsumed is a no-op when there is no consumed dir`(@TempDir dir: Path) {
        val comments = Files.createDirectories(dir.resolve("comments"))

        watcher(comments, store(comments)).sweepConsumed() // must not throw

        assertFalse(Files.exists(comments.resolve("consumed")))
    }
}
