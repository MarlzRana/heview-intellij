package com.marlzrana.heview.storage

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class HeviewPathsTest {
    @Test
    fun `root and comments dir match the shared on-disk contract`() {
        assertTrue(HeviewPaths.root.endsWith(".heview"), HeviewPaths.root.toString())
        assertTrue(
            HeviewPaths.commentsDir.endsWith(Path.of(".heview", "comments")),
            HeviewPaths.commentsDir.toString(),
        )
    }
}
