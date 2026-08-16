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
    private val home: Path = Path(System.getProperty("user.home"))

    val root: Path = home.resolve(".heview")

    /** The shared comment pool — one `<uuid>.json` per thread, consumed first-come across agents. */
    val commentsDir: Path = root.resolve("comments")

    /** Per-agent hook script dirs heview owns and extracts into (plan.html §4). */
    val claudeCodeHooksDir: Path = root.resolve("claude-code").resolve("hooks")
    val codexHooksDir: Path = root.resolve("codex").resolve("hooks")

    /** Agent config files heview *edits but does not own* — where hooks are registered (plan.html §4). */
    val claudeSettings: Path = home.resolve(".claude").resolve("settings.json")
    val codexConfigToml: Path = home.resolve(".codex").resolve("config.toml")
    val codexHooksJson: Path = home.resolve(".codex").resolve("hooks.json")
}
