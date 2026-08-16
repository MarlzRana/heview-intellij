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

    @Test
    fun `consumed dir is nested under the comments pool`() {
        // Nesting matters: the pool's `*.json` glob skips this subdir, so hydration and the injectors
        // never treat a consumed file as pending (plan.html §4).
        assertEquals(HeviewPaths.commentsDir.resolve("consumed"), HeviewPaths.consumedDir)
    }
}
