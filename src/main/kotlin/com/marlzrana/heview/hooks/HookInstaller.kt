package com.marlzrana.heview.hooks

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.marlzrana.heview.storage.HeviewPaths
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Installs the coding-agent hooks heview ships (plan.html §4/§6): extracts the bundled scripts into
 * `~/.heview/<agent>/hooks/`, detects whether each agent's CLI is on `PATH`, and registers the hook
 * in the agent's own config (`~/.claude/settings.json`, `~/.codex/{config.toml,hooks.json}`).
 *
 * Registration is idempotent (dedup by the injector filename in the command) and preserves unrelated
 * config; writes are atomic (tmp→rename) so a crash can't corrupt the user's config. Paths, `PATH`,
 * and the warning sink are injectable so everything is unit-testable against a temp directory without
 * a running IDE. heview's dedup marker never matches reviewa's, so both can register side-by-side.
 */
class HookInstaller(
    private val claudeCodeHooksDir: Path = HeviewPaths.claudeCodeHooksDir,
    private val codexHooksDir: Path = HeviewPaths.codexHooksDir,
    private val claudeSettings: Path = HeviewPaths.claudeSettings,
    private val codexConfigToml: Path = HeviewPaths.codexConfigToml,
    private val codexHooksJson: Path = HeviewPaths.codexHooksJson,
    private val pathEnv: String = System.getenv("PATH") ?: "",
    private val warn: (String) -> Unit = { LOG.warn(it) },
) {
    /** For each agent whose CLI is on `PATH`: extract its script(s) and register the hook. */
    fun installAll() {
        if (hasCli("claude")) {
            extractClaudeCodeScripts()
            registerClaudeCode()
        }
        if (hasCli("codex")) {
            extractCodexScripts()
            registerCodex()
        }
    }

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

    /** Register the Claude Code `UserPromptSubmit` hook in `~/.claude/settings.json`. */
    fun registerClaudeCode() {
        val command = "bash ${claudeCodeHooksDir.resolve(CLAUDE_SH)}"
        registerUserPromptSubmit(claudeSettings, command, marker = CLAUDE_SH)
    }

    /** Enable codex hooks and register the `UserPromptSubmit` hook in `~/.codex/hooks.json`. */
    fun registerCodex() {
        ensureCodexHooksEnabled()
        val command = "python3 ${codexHooksDir.resolve(CODEX_PY)}"
        registerUserPromptSubmit(codexHooksJson, command, marker = CODEX_PY)
    }

    /**
     * Append a `{ hooks: [{ type: command, command, timeout: 10 }] }` entry to
     * `hooks.UserPromptSubmit` in the JSON at [configPath], unless one whose command already contains
     * [marker] is present. Both Claude `settings.json` and Codex `hooks.json` share this shape.
     */
    private fun registerUserPromptSubmit(configPath: Path, command: String, marker: String) {
        val root = readJsonObject(configPath) ?: JsonObject()
        val hooks = root.get("hooks")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { root.add("hooks", it) }
        val entries = hooks.get("UserPromptSubmit")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray().also { hooks.add("UserPromptSubmit", it) }

        val alreadyRegistered = entries.any { entry ->
            val inner = entry.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("hooks")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@any false
            inner.any { h ->
                val cmd = h.takeIf { it.isJsonObject }?.asJsonObject?.get("command")
                cmd != null && cmd.isJsonPrimitive && cmd.asString.contains(marker)
            }
        }
        if (alreadyRegistered) return

        val hook = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", command)
            addProperty("timeout", 10)
        }
        val entry = JsonObject().apply { add("hooks", JsonArray().apply { add(hook) }) }
        entries.add(entry)
        writeAtomically(configPath, GSON.toJson(root))
    }

    /**
     * Ensure `[features] codex_hooks = true` in `~/.codex/config.toml`, preserving the rest of the
     * file via string edits (no TOML library). Warns if the user has it explicitly `false`.
     */
    private fun ensureCodexHooksEnabled() {
        val content = try {
            Files.readString(codexConfigToml)
        } catch (e: IOException) {
            writeAtomically(codexConfigToml, "[features]\ncodex_hooks = true\n")
            return
        }

        val features = FEATURES_HEADER.find(content)
        if (features == null) {
            writeAtomically(codexConfigToml, content.trimEnd() + "\n\n[features]\ncodex_hooks = true\n")
            return
        }

        // The [features] section runs from its header to the next section header (or EOF).
        val afterHeader = content.substring(features.range.first)
        val nextSection = SECTION_HEADER.find(afterHeader.substring(1))?.range?.first
        val featuresSection = if (nextSection == null) afterHeader else afterHeader.substring(0, nextSection + 1)

        val hooksFlag = CODEX_HOOKS_FLAG.find(featuresSection)
        when {
            hooksFlag == null ->
                writeAtomically(codexConfigToml, content.replaceFirst(FEATURES_HEADER, "[features]\ncodex_hooks = true"))
            hooksFlag.groupValues[1] == "false" ->
                warn("Codex hooks are disabled (codex_hooks = false in ~/.codex/config.toml); heview's Codex integration won't run until it's true.")
            // already true → nothing to do
        }
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

    private fun readJsonObject(path: Path): JsonObject? =
        try {
            JsonParser.parseString(Files.readString(path)).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null // absent or invalid JSON → start fresh (matches reviewa)
        }

    /** Write [text] to [path] via a temp file + atomic rename, so a crash can't corrupt the config. */
    private fun writeAtomically(path: Path, text: String) {
        Files.createDirectories(path.parent)
        val tmp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        try {
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    companion object {
        const val CLAUDE_JS = "user_prompt_submit_insert_comments_hook.js"
        const val CLAUDE_SH = "user_prompt_submit_insert_comments_hook.sh"
        const val CODEX_PY = "user_prompt_submit_insert_comments_hook.py"

        private val LOG = logger<HookInstaller>()
        private val EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x")
        private val GSON = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
        private val FEATURES_HEADER = Regex("^\\[features\\]", RegexOption.MULTILINE)
        private val SECTION_HEADER = Regex("^\\[", RegexOption.MULTILINE)
        private val CODEX_HOOKS_FLAG = Regex("^\\s*codex_hooks\\s*=\\s*(true|false)", RegexOption.MULTILINE)
    }
}
