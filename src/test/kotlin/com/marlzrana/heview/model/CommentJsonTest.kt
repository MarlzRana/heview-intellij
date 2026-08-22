package com.marlzrana.heview.model

import com.marlzrana.heview.sampleComment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommentJsonTest {
    @Test
    fun `encodes the shared snake_case schema keys`() {
        val json = CommentJson.encode(sampleComment())
        for (key in listOf(
            "uuid", "status", "created_at", "workspace", "abs_path", "logical_abs_path",
            "line_number", "line_content", "side", "content",
        )) {
            assertTrue(json.contains("\"$key\""), "missing key '$key' in: $json")
        }
    }

    @Test
    fun `encodes enum values as reviewa strings`() {
        val json = CommentJson.encode(sampleComment(status = CommentStatus.PENDING, side = CommentSide.FILE))
        assertTrue(json.contains("\"pending\""), json)
        assertTrue(json.contains("\"file\""), json)
    }

    @Test
    fun `encodes processed, addition and removal as reviewa wire strings`() {
        assertTrue(CommentJson.encode(sampleComment(status = CommentStatus.PROCESSED)).contains("\"processed\""))
        assertTrue(CommentJson.encode(sampleComment(side = CommentSide.ADDITION)).contains("\"addition\""))
        assertTrue(CommentJson.encode(sampleComment(side = CommentSide.REMOVAL)).contains("\"removal\""))
    }

    @Test
    fun `does not HTML-escape angle brackets and ampersands`() {
        val json = CommentJson.encode(sampleComment().copy(content = "a <T> && b = c"))
        assertTrue(json.contains("a <T> && b = c"), json)
    }

    @Test
    fun `omits intended_consumer when null`() {
        val json = CommentJson.encode(sampleComment(intendedConsumer = null))
        assertFalse(json.contains("intended_consumer"), json)
    }

    @Test
    fun `includes intended_consumer as claude_code when set`() {
        val json = CommentJson.encode(sampleComment(intendedConsumer = IntendedConsumer.CLAUDE_CODE))
        assertTrue(json.contains("\"intended_consumer\""), json)
        assertTrue(json.contains("\"claude_code\""), json)
    }

    @Test
    fun `round-trips through encode then decode`() {
        val original = sampleComment(intendedConsumer = IntendedConsumer.CLAUDE_CODE)
        val decoded = CommentJson.decode(CommentJson.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `encodes the nested replies array with each snake_case key`() {
        val json = CommentJson.encode(
            sampleComment(
                replies = listOf(
                    HeviewReply("first", CommentStatus.PENDING, "marlzrana", "2026-08-15T00:00:00.000Z", id = "r1"),
                    HeviewReply("second", CommentStatus.PROCESSED, "marlzrana", "2026-08-16T00:00:00.000Z", id = "r2"),
                ),
            ),
        )
        assertTrue(json.contains("\"replies\""), json)
        for (key in listOf("\"content\"", "\"status\"", "\"author\"", "\"created_at\"", "\"id\"")) {
            assertTrue(json.contains(key), "missing nested reply key $key in: $json")
        }
    }

    @Test
    fun `decodes an external payload preserving every reply field`() {
        val payload = """
            {
              "uuid": "t", "status": "pending", "created_at": "2026-08-15T00:00:00.000Z",
              "workspace": "/repo", "abs_path": "/repo/src/Foo.kt", "logical_abs_path": "/repo/src/Foo.kt",
              "line_number": 7, "line_content": "x", "side": "file", "content": "first",
              "replies": [
                {"content":"first","status":"pending","author":"a","created_at":"2026-08-15T00:00:00.000Z","id":"r1"},
                {"content":"second","status":"processed","author":"b","created_at":"2026-08-16T00:00:00.000Z","id":"r2"}
              ]
            }
        """.trimIndent()
        val replies = CommentJson.decode(payload).replies ?: error("replies did not decode")
        assertEquals(
            listOf(
                HeviewReply("first", CommentStatus.PENDING, "a", "2026-08-15T00:00:00.000Z", id = "r1"),
                HeviewReply("second", CommentStatus.PROCESSED, "b", "2026-08-16T00:00:00.000Z", id = "r2"),
            ),
            replies,
        )
    }

    @Test
    fun `encodes the generation fence token and decodes an absent one as 0`() {
        // The generation CAS token is a heview superset field: always emitted, round-trips, and a
        // foreign/legacy payload without the key decodes to 0 (the pre-fence baseline).
        val json = CommentJson.encode(sampleComment().copy(generation = 3))
        assertTrue(json.contains("\"generation\""), json)
        assertEquals(3, CommentJson.decode(json).generation)

        val legacy = """
            {"uuid":"t","status":"pending","created_at":"2026-08-15T00:00:00.000Z","workspace":"/repo",
             "abs_path":"/repo/src/Foo.kt","logical_abs_path":"/repo/src/Foo.kt","line_number":7,
             "line_content":"x","side":"file","content":"hi"}
        """.trimIndent()
        assertEquals(0, CommentJson.decode(legacy).generation)
    }

    @Test
    fun `decodes a reviewa-style payload written by a shared hook`() {
        val payload = """
            {
              "uuid": "abc",
              "status": "pending",
              "created_at": "2026-08-15T00:00:00.000Z",
              "workspace": "/repo",
              "abs_path": "/repo/src/Foo.kt",
              "logical_abs_path": "/repo/src/Foo.kt",
              "line_number": 7,
              "line_content": "x",
              "side": "addition",
              "content": "hi"
            }
        """.trimIndent()
        val c = CommentJson.decode(payload)
        assertEquals("abc", c.uuid)
        assertEquals(CommentStatus.PENDING, c.status)
        assertEquals(CommentSide.ADDITION, c.side)
        assertEquals(7, c.lineNumber)
        assertNull(c.intendedConsumer)
    }
}
