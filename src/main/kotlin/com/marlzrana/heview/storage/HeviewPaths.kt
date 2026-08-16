package com.marlzrana.heview.storage

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Resolves the shared `~/.heview` on-disk layout + the agents' config paths (plan.html §4).
 *
 * Uses the `user.home` system property. NOTE: `-Duser.home=…` only isolates the in-IDE pool/UI and the
 * files heview *writes* (pool, extracted scripts, registration targets). It does NOT isolate the hook
 * *loop*: the agent CLIs and the extracted scripts (`.sh` → `$HOME`, Node `os.homedir()`, Python
 * `expanduser("~")`) resolve the real OS `$HOME` at runtime, so a sandboxed home can't dogfood
 * end-to-end injection — that always reads/writes the real `~/.heview/comments`.
 */
object HeviewPaths {
    private val home: Path = Path(System.getProperty("user.home"))

    val root: Path = home.resolve(".heview")

    /** The shared comment pool — one `<uuid>.json` per thread, consumed first-come across agents. */
    val commentsDir: Path = root.resolve("comments")

    /**
     * Where an agent hook MOVES a comment file when it consumes it (Phase 3), instead of unlinking it —
     * the intent signal the consumption watcher reads to mark a thread *Seen* (a bare vanish from
     * [commentsDir] is a peer/user delete instead). Nested under [commentsDir]; the pool's `*.json` glob
     * skips this subdirectory so hydration and the injectors never treat consumed files as pending.
     */
    val consumedDir: Path = commentsDir.resolve("consumed")

    /** Per-agent hook script dirs heview owns and extracts into (plan.html §4). */
    val claudeCodeHooksDir: Path = root.resolve("claude-code").resolve("hooks")
    val codexHooksDir: Path = root.resolve("codex").resolve("hooks")

    /** Agent config files heview *edits but does not own* — where hooks are registered (plan.html §4). */
    val claudeSettings: Path = home.resolve(".claude").resolve("settings.json")
    val codexConfigToml: Path = home.resolve(".codex").resolve("config.toml")
    val codexHooksJson: Path = home.resolve(".codex").resolve("hooks.json")
}
