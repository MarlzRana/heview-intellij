package com.marlzrana.heview.storage

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Resolves the shared `~/.heview` on-disk layout (plan.html §4).
 *
 * Uses the `user.home` system property so the sandbox IDE can be pointed at a throwaway home via
 * `-Duser.home=…` for isolated dogfooding (README / plan.html §11).
 */
object HeviewPaths {
    val root: Path = Path(System.getProperty("user.home")).resolve(".heview")

    /** The shared comment pool — one `<uuid>.json` per thread, consumed first-come across agents. */
    val commentsDir: Path = root.resolve("comments")
}
