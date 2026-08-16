package com.marlzrana.heview

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeviewStartupActivityTest {
    @Test
    fun `hooks install only outside unit-test and headless mode`() {
        // Safety-critical: never mutate the real ~/.claude / ~/.codex during tests or headless runs.
        assertFalse(HeviewStartupActivity.installationAllowed(isUnitTestMode = true, isHeadless = false))
        assertFalse(HeviewStartupActivity.installationAllowed(isUnitTestMode = false, isHeadless = true))
        assertFalse(HeviewStartupActivity.installationAllowed(isUnitTestMode = true, isHeadless = true))
        assertTrue(HeviewStartupActivity.installationAllowed(isUnitTestMode = false, isHeadless = false))
    }
}
