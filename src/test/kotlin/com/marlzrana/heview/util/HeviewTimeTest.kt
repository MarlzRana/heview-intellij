package com.marlzrana.heview.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class HeviewTimeTest {
    @Test
    fun `formats an instant as fixed-millis ISO with Z`() {
        assertEquals(
            "2026-08-16T12:34:56.789Z",
            HeviewTime.format(Instant.parse("2026-08-16T12:34:56.789Z")),
        )
    }

    @Test
    fun `pads to exactly three fractional digits`() {
        assertEquals("2026-08-16T00:00:00.000Z", HeviewTime.format(Instant.parse("2026-08-16T00:00:00Z")))
        assertEquals("2026-08-16T00:00:00.010Z", HeviewTime.format(Instant.parse("2026-08-16T00:00:00.01Z")))
    }

    @Test
    fun `nowIso matches the reviewa toISOString profile`() {
        assertTrue(HeviewTime.nowIso().matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")))
    }
}
