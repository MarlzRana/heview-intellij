package com.marlzrana.heview.model

import com.marlzrana.heview.sampleComment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommentEditsTest {
    private val laterTs = "2026-08-16T09:30:00.000Z"

    @Test
    fun `withReply appends the reply after a blank line and revives to pending`() {
        val seen = sampleComment(uuid = "u1", status = CommentStatus.PROCESSED)
            .copy(content = "make this a const")

        val replied = seen.withReply("also rename x", laterTs)

        // reviewa persists a thread's content as its texts joined by a blank line.
        assertEquals("make this a const\n\nalso rename x", replied.content)
        assertEquals(CommentStatus.PENDING, replied.status)
        assertEquals(laterTs, replied.createdAt) // bumped — drives injection ordering
    }

    @Test
    fun `withContent replaces the content and revives to pending`() {
        val seen = sampleComment(uuid = "u1", status = CommentStatus.PROCESSED)

        val edited = seen.withContent("completely new text", laterTs)

        assertEquals("completely new text", edited.content)
        assertEquals(CommentStatus.PENDING, edited.status)
        assertEquals(laterTs, edited.createdAt)
    }

    @Test
    fun `revived flips status to pending and bumps created_at without touching content`() {
        val seen = sampleComment(uuid = "u1", status = CommentStatus.PROCESSED)
            .copy(content = "please fix")

        val repended = seen.revived(laterTs)

        assertEquals("please fix", repended.content) // unchanged
        assertEquals(CommentStatus.PENDING, repended.status)
        assertEquals(laterTs, repended.createdAt)
    }

    @Test
    fun `the transitions preserve every other shared-contract field`() {
        val original = sampleComment(uuid = "u1", status = CommentStatus.PROCESSED)
        val repended = original.revived(laterTs)
        // Only status + created_at move; uuid/path/line/side/etc. must survive verbatim.
        assertEquals(original.copy(status = CommentStatus.PENDING, createdAt = laterTs), repended)
    }
}
