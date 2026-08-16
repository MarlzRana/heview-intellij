package com.marlzrana.heview.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class HeviewPathsTest {
    @Test
    fun `root and comments dir match the shared on-disk contract`() {
        val expectedRoot = Path.of(System.getProperty("user.home")).resolve(".heview")
        assertEquals(expectedRoot, HeviewPaths.root)
        assertEquals(expectedRoot.resolve("comments"), HeviewPaths.commentsDir)
    }
}
