package com.marlzrana.heview.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NewCommentTest {
    @Test
    fun `builds a v1 file comment with 1-based line and mirrored logical path`() {
        val c = newFileComment(
            workspace = "/repo",
            absPath = "/repo/src/Foo.kt",
            line0Based = 0,
            lineContent = "val x = 1",
            content = "make this a const",
            author = "marlzrana",
            createdAt = "2026-08-16T00:00:00.000Z",
            uuid = "u1",
        )
        assertEquals(1, c.lineNumber, "0-based editor line must become a 1-based line_number")
        assertEquals("/repo/src/Foo.kt", c.absPath)
        assertEquals(c.absPath, c.logicalAbsPath)
        assertEquals(CommentSide.FILE, c.side)
        assertEquals(CommentStatus.PENDING, c.status)
        assertEquals("/repo", c.workspace)
        assertEquals("make this a const", c.content)
        assertEquals("val x = 1", c.lineContent)
        assertEquals("2026-08-16T00:00:00.000Z", c.createdAt)
        assertNull(c.intendedConsumer)
    }

    @Test
    fun `seeds a single pending reply mirroring the content`() {
        val c = newFileComment(
            workspace = "/w",
            absPath = "/w/a.kt",
            line0Based = 0,
            lineContent = "",
            content = "the note",
            author = "marlzrana",
            createdAt = "2026-08-16T00:00:00.000Z",
        )
        val reply = c.replies?.singleOrNull() ?: error("expected exactly one seeded reply")
        assertEquals("the note", reply.content)
        assertEquals(CommentStatus.PENDING, reply.status)
        assertEquals("marlzrana", reply.author)
        assertEquals("2026-08-16T00:00:00.000Z", reply.createdAt)
        assertTrue(reply.id.isNotBlank()) // a stable id was assigned
    }

    @Test
    fun `line_number is the 0-based editor line plus one`() {
        assertEquals(1, newFileComment("/w", "/w/a.kt", 0, "", "c", "me", "t").lineNumber)
        assertEquals(6, newFileComment("/w", "/w/a.kt", 5, "", "c", "me", "t").lineNumber)
        assertEquals(100, newFileComment("/w", "/w/a.kt", 99, "", "c", "me", "t").lineNumber)
    }
}
