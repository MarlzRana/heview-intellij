package com.marlzrana.heview.storage

import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.sampleComment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CommentStoreTest {
    @Test
    fun `save writes a file and indexes the record`(@TempDir dir: Path) {
        val store = CommentStore(dir)
        store.save(sampleComment(uuid = "u1"))
        assertTrue(Files.exists(dir.resolve("u1.json")))
        assertEquals("u1", store.get("u1")?.uuid)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `pending and processed counts reflect statuses`(@TempDir dir: Path) {
        val store = CommentStore(dir)
        store.save(sampleComment(uuid = "p1", status = CommentStatus.PENDING))
        store.save(sampleComment(uuid = "p2", status = CommentStatus.PENDING))
        store.save(sampleComment(uuid = "d1", status = CommentStatus.PROCESSED))
        assertEquals(2, store.pendingCount())
        assertEquals(1, store.processedCount())
    }

    @Test
    fun `save upserts an existing record in place`(@TempDir dir: Path) {
        val store = CommentStore(dir)
        store.save(sampleComment(uuid = "u1", status = CommentStatus.PENDING))
        store.save(sampleComment(uuid = "u1", status = CommentStatus.PROCESSED))
        assertEquals(1, store.all().size)
        assertEquals(CommentStatus.PROCESSED, store.get("u1")?.status)
    }

    @Test
    fun `delete removes the file and the record`(@TempDir dir: Path) {
        val store = CommentStore(dir)
        store.save(sampleComment(uuid = "u1"))
        store.delete("u1")
        assertFalse(Files.exists(dir.resolve("u1.json")))
        assertNull(store.get("u1"))
    }

    @Test
    fun `change listener fires on save and delete`(@TempDir dir: Path) {
        val store = CommentStore(dir)
        var count = 0
        store.addChangeListener { count++ }
        store.save(sampleComment(uuid = "u1"))
        store.delete("u1")
        assertEquals(2, count)
    }
}
