package com.marlzrana.heview.hooks

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.EnvironmentUtil
import com.marlzrana.heview.storage.HeviewPaths
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Installs the coding-agent hooks heview ships (plan.html §4/§6): extracts the bundled scripts into
 * `~/.heview/<agent>/hooks/`, detects whether each agent's CLI is on `PATH`, and registers the hook
 * in the agent's own config (`~/.claude/settings.json`, `~/.codex/{config.toml,hooks.json}`).
 *
 * Registration is idempotent (dedup by the injector filename in the command) and preserves unrelated
 * config. Safety: a present-but-unparseable config is left untouched (never clobbered); writes are
 * atomic (tmp→rename) and follow a symlinked config to its real target; the command path is
 * shell-quoted. Paths, `PATH`, and the warning sink are injectable so everything is unit-testable
 * against a temp dir. heview's dedup marker never matches reviewa's, so both can register side-by-side.
 */
class HookInstaller(
    private val claudeCodeHooksDir: Path = HeviewPaths.claudeCodeHooksDir,
    private val codexHooksDir: Path = HeviewPaths.codexHooksDir,
    private val claudeSettings: Path = HeviewPaths.claudeSettings,
    private val codexConfigToml: Path = HeviewPaths.codexConfigToml,
    private val codexHooksJson: Path = HeviewPaths.codexHooksJson,
    // EnvironmentUtil loads the user's *login-shell* PATH; a GUI-launched IDE (Toolbox/Dock/Finder)
    // otherwise sees only a bare PATH that misses /opt/homebrew/bin, ~/.local/bin, nvm, etc.
    private val pathEnv: String = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH").orEmpty(),
    private val warn: (String) -> Unit = { LOG.warn(it) },
) {
    /**
     * Extract every bundled script (so upgrades always refresh on-disk copies, even for a CLI missing
     * from the GUI PATH), then register an agent only if its script extracted AND its CLI is present.
     */
    fun installAll() {
        val claudeExtracted = extractClaudeCodeScripts()
        val codexExtracted = extractCodexScripts()
        if (claudeExtracted && hasCli("claude")) registerClaudeCode()
        if (codexExtracted && hasCli("codex")) registerCodex()
    }

    /** True if [name] resolves to an executable on `PATH` (a `which`-equivalent, no subprocess). */
    fun hasCli(name: String): Boolean =
        pathEnv.split(File.pathSeparatorChar)
            .filter { it.isNotEmpty() }
            .any { dir ->
                runCatching { Files.isExecutable(Path.of(dir).resolve(name)) }.getOrDefault(false)
            }

    /** Extract (overwriting) the Claude Code injector + its bash wrapper. True if both landed. */
    fun extractClaudeCodeScripts(): Boolean {
        val js = extract("claude-code", CLAUDE_JS, claudeCodeHooksDir)
        val sh = extract("claude-code", CLAUDE_SH, claudeCodeHooksDir)
        return js && sh
    }

    /** Extract (overwriting) the Codex injector. True if it landed. */
    fun extractCodexScripts(): Boolean = extract("codex", CODEX_PY, codexHooksDir)

    /** Register the Claude Code `UserPromptSubmit` hook in `~/.claude/settings.json`. */
    fun registerClaudeCode() {
        val command = "bash ${shellQuote(claudeCodeHooksDir.resolve(CLAUDE_SH).toString())}"
        registerUserPromptSubmit(claudeSettings, command, marker = CLAUDE_SH)
    }

    /** Enable codex hooks and register the `UserPromptSubmit` hook in `~/.codex/hooks.json`. */
    fun registerCodex() {
        ensureCodexHooksEnabled()
        val command = "python3 ${shellQuote(codexHooksDir.resolve(CODEX_PY).toString())}"
        registerUserPromptSubmit(codexHooksJson, command, marker = CODEX_PY)
    }

    /**
     * Append a `{ hooks: [{ type: command, command, timeout: 10 }] }` entry to
     * `hooks.UserPromptSubmit` in the JSON at [configPath], unless one whose command already contains
     * [marker] is present. Both Claude `settings.json` and Codex `hooks.json` share this shape.
     */
    private fun registerUserPromptSubmit(configPath: Path, command: String, marker: String) {
        val root = loadConfigForEdit(configPath) ?: return // present-but-unparseable → leave it untouched
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

        // The [features] section runs from its header to the next section header (or EOF). Search for
        // the next section AFTER this header so its own '[' isn't re-matched — no substring/offset dance.
        val headerEnd = features.range.last + 1
        val nextSection = SECTION_HEADER.find(content, startIndex = headerEnd)?.range?.first
        val featuresSection = content.substring(features.range.first, nextSection ?: content.length)

        val hooksFlag = CODEX_HOOKS_FLAG.find(featuresSection)
        when {
            hooksFlag == null ->
                // Insert right after the matched header, preserving the user's exact header text.
                writeAtomically(codexConfigToml, content.substring(0, headerEnd) + "\ncodex_hooks = true" + content.substring(headerEnd))
            hooksFlag.groupValues[1] == "false" ->
                warn("Codex hooks are disabled (codex_hooks = false in ~/.codex/config.toml); heview's Codex integration won't run until it's true.")
            // already true → nothing to do
        }
    }

    /**
     * Copy a bundled script resource to `<destDir>/<filename>`, overwriting so upgrades take effect,
     * and mark it executable. Best-effort: a missing resource or a chmod failure is logged, not thrown.
     */
    private fun extract(agent: String, filename: String, destDir: Path): Boolean {
        val resource = "/hook-scripts/$agent/$filename"
        return try {
            Files.createDirectories(destDir)
            val dest = destDir.resolve(filename)
            val stream = javaClass.getResourceAsStream(resource)
            if (stream == null) {
                LOG.warn("heview: bundled hook script missing from the plugin: $resource")
                return false
            }
            stream.use { Files.copy(it, dest, StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.setPosixFilePermissions(dest, EXECUTABLE) }
                .onFailure { LOG.debug("heview: could not chmod $dest", it) }
            true
        } catch (e: IOException) {
            LOG.warn("heview: failed to extract hook script $resource", e)
            false
        }
    }

    /** POSIX single-quote a path so a home dir with spaces doesn't break the hook command. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Load a config for editing. An **absent** file → a fresh object (we'll create it). A file that is
     * present but **unreadable or not a JSON object** → `null`, so the caller leaves it untouched rather
     * than clobbering recoverable user content (safer than reviewa, which starts fresh on any read/parse
     * failure and would overwrite a hand-edited-mid-save or transiently-unreadable config).
     */
    private fun loadConfigForEdit(path: Path): JsonObject? {
        val text = try {
            Files.readString(path)
        } catch (e: NoSuchFileException) {
            return JsonObject()
        } catch (e: IOException) {
            warn("heview: could not read ${path}; leaving it untouched and skipping hook registration.")
            return null
        }
        return try {
            JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
                ?: run { warn("heview: ${path} is not a JSON object; leaving it untouched."); null }
        } catch (e: Exception) {
            warn("heview: ${path} is not valid JSON; leaving it untouched and skipping hook registration.")
            null
        }
    }

    /**
     * Write [text] to [path] via a temp file + atomic rename, so a crash can't corrupt the config. If
     * [path] is a symlink (e.g. a dotfiles-managed config), resolve to its real target and rename over
     * that, leaving the symlink itself in place rather than replacing it with a regular file.
     */
    private fun writeAtomically(path: Path, text: String) {
        Files.createDirectories(path.parent)
        val target = if (Files.exists(path)) path.toRealPath() else path
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
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
        // Tolerate TOML header whitespace (`[ features ]`) so we edit the existing table instead of
        // appending a duplicate one (which would make config.toml invalid).
        private val FEATURES_HEADER = Regex("^\\s*\\[\\s*features\\s*\\]", RegexOption.MULTILINE)
        private val SECTION_HEADER = Regex("^\\s*\\[", RegexOption.MULTILINE)
        private val CODEX_HOOKS_FLAG = Regex("^\\s*codex_hooks\\s*=\\s*(true|false)", RegexOption.MULTILINE)
    }
}
