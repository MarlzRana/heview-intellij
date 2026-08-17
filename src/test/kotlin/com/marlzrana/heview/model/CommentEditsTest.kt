package com.marlzrana.heview.model

import com.marlzrana.heview.sampleComment
import com.marlzrana.heview.sampleReply
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommentEditsTest {
    private val now = "2026-08-16T09:30:00.000Z"

    @Test
    fun `recomputed derives content from the PENDING replies joined by a blank line`() {
        val thread = sampleComment(uuid = "u1").copy(
            replies = listOf(
                sampleReply(content = "first", status = CommentStatus.PENDING),
                sampleReply(content = "second", status = CommentStatus.PENDING),
            ),
        )

        val out = thread.recomputed(now)

        assertEquals("first\n\nsecond", out.content) // reviewa's actionable-texts join
        assertEquals(CommentStatus.PENDING, out.status)
        assertEquals(now, out.createdAt) // bumped
    }

    @Test
    fun `recomputed excludes SEEN replies from content and stays pending if any remain`() {
        val thread = sampleComment(uuid = "u1").copy(
            replies = listOf(
                sampleReply(content = "seen one", status = CommentStatus.PROCESSED),
                sampleReply(content = "still actionable", status = CommentStatus.PENDING),
            ),
        )

        val out = thread.recomputed(now)

        assertEquals("still actionable", out.content) // the Seen reply is not injected
        assertEquals(CommentStatus.PENDING, out.status)
    }

    @Test
    fun `recomputed marks the thread PROCESSED with empty content when no reply is actionable`() {
        val thread = sampleComment(uuid = "u1").copy(
            replies = listOf(
                sampleReply(content = "a", status = CommentStatus.PROCESSED),
                sampleReply(content = "b", status = CommentStatus.PROCESSED),
            ),
        )

        val out = thread.recomputed(now)

        assertEquals("", out.content)
        assertEquals(CommentStatus.PROCESSED, out.status)
    }

    @Test
    fun `normalizedReplies passes through an existing replies list`() {
        val replies = listOf(sampleReply(content = "x"), sampleReply(content = "y"))
        val thread = sampleComment(uuid = "u1").copy(replies = replies)
        assertEquals(replies, thread.normalizedReplies("fallback"))
    }

    @Test
    fun `normalizedReplies reconstructs one reply from content for a legacy file`() {
        val legacy = sampleComment(uuid = "u1", status = CommentStatus.PENDING)
            .copy(content = "solo note", replies = null)

        val out = legacy.normalizedReplies("marlzrana")

        assertEquals(
            listOf(HeviewReply("solo note", CommentStatus.PENDING, "marlzrana", legacy.createdAt)),
            out,
        )
    }
}
