package com.marlzrana.heview.hooks

import com.intellij.openapi.diagnostic.logger
import com.marlzrana.heview.storage.HeviewPaths
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Installs the coding-agent hook scripts heview ships (plan.html §4/§6): it extracts the bundled
 * scripts into `~/.heview/<agent>/hooks/` and detects whether each agent's CLI is on `PATH`.
 *
 * Registration into the agents' own config files (`~/.claude/settings.json`, `~/.codex/…`) is a
 * separate step. Paths and the `PATH` value are injectable so the logic is unit-testable against a
 * temp directory without a running IDE; script bytes are read from the plugin classpath resources.
 */
class HookInstaller(
    private val claudeCodeHooksDir: Path = HeviewPaths.claudeCodeHooksDir,
    private val codexHooksDir: Path = HeviewPaths.codexHooksDir,
    private val pathEnv: String = System.getenv("PATH") ?: "",
) {
    /** True if [name] resolves to an executable on `PATH` (a `which`-equivalent, no subprocess). */
    fun hasCli(name: String): Boolean =
        pathEnv.split(File.pathSeparatorChar)
            .filter { it.isNotEmpty() }
            .any { dir ->
                runCatching { Files.isExecutable(Path.of(dir).resolve(name)) }.getOrDefault(false)
            }

    /** Extract (overwriting) the Claude Code injector + its bash wrapper into the hooks dir. */
    fun extractClaudeCodeScripts() {
        extract("claude-code", CLAUDE_JS, claudeCodeHooksDir)
        extract("claude-code", CLAUDE_SH, claudeCodeHooksDir)
    }

    /** Extract (overwriting) the Codex injector into the hooks dir. */
    fun extractCodexScripts() {
        extract("codex", CODEX_PY, codexHooksDir)
    }

    /**
     * Copy a bundled script resource to `<destDir>/<filename>`, overwriting so upgrades take effect,
     * and mark it executable. Best-effort: a missing resource or a chmod failure is logged, not thrown.
     */
    private fun extract(agent: String, filename: String, destDir: Path) {
        val resource = "/hook-scripts/$agent/$filename"
        try {
            Files.createDirectories(destDir)
            val dest = destDir.resolve(filename)
            val stream = javaClass.getResourceAsStream(resource)
            if (stream == null) {
                LOG.warn("heview: bundled hook script missing from the plugin: $resource")
                return
            }
            stream.use { Files.copy(it, dest, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.setPosixFilePermissions(dest, EXECUTABLE) }
                .onFailure { LOG.debug("heview: could not chmod $dest", it) }
        } catch (e: IOException) {
            LOG.warn("heview: failed to extract hook script $resource", e)
        }
    }

    companion object {
        const val CLAUDE_JS = "user_prompt_submit_insert_comments_hook.js"
        const val CLAUDE_SH = "user_prompt_submit_insert_comments_hook.sh"
        const val CODEX_PY = "user_prompt_submit_insert_comments_hook.py"

        private val LOG = logger<HookInstaller>()
        private val EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x")
    }
}
