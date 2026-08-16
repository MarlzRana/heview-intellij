package com.marlzrana.heview.hooks

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class HookInstallerTest {
    private fun installer(dir: Path, pathEnv: String = "", warn: (String) -> Unit = {}) = HookInstaller(
        claudeCodeHooksDir = dir.resolve("claude-code/hooks"),
        codexHooksDir = dir.resolve("codex/hooks"),
        claudeSettings = dir.resolve("dot-claude/settings.json"),
        codexConfigToml = dir.resolve("dot-codex/config.toml"),
        codexHooksJson = dir.resolve("dot-codex/hooks.json"),
        pathEnv = pathEnv,
        warn = warn,
    )

    /** The command string of the single UserPromptSubmit hook in a Claude/Codex-shaped config file. */
    private fun soleCommand(configFile: Path): String {
        val root = JsonParser.parseString(Files.readString(configFile)).asJsonObject
        val entries = root.getAsJsonObject("hooks").getAsJsonArray("UserPromptSubmit")
        return entries[0].asJsonObject.getAsJsonArray("hooks")[0].asJsonObject.get("command").asString
    }

    private fun userPromptSubmitSize(configFile: Path): Int =
        JsonParser.parseString(Files.readString(configFile)).asJsonObject
            .getAsJsonObject("hooks").getAsJsonArray("UserPromptSubmit").size()

    @Test
    fun `extracts the Claude Code injector and wrapper, executable, pointing at the heview pool`(@TempDir dir: Path) {
        installer(dir).extractClaudeCodeScripts()
        val js = dir.resolve("claude-code/hooks/${HookInstaller.CLAUDE_JS}")
        val sh = dir.resolve("claude-code/hooks/${HookInstaller.CLAUDE_SH}")
        assertTrue(Files.isExecutable(js))
        assertTrue(Files.isExecutable(sh))
        val jsText = Files.readString(js)
        assertTrue(jsText.startsWith("#!/usr/bin/env node"))
        assertTrue(jsText.contains(".heview")) // reads ~/.heview/comments, not reviewa's pool
        assertTrue(Files.readString(sh).contains(".heview/claude-code/hooks/${HookInstaller.CLAUDE_JS}"))
    }

    @Test
    fun `extracts the Codex injector, executable, as python`(@TempDir dir: Path) {
        installer(dir).extractCodexScripts()
        val py = dir.resolve("codex/hooks/${HookInstaller.CODEX_PY}")
        assertTrue(Files.isExecutable(py))
        val text = Files.readString(py)
        assertTrue(text.startsWith("#!/usr/bin/env python3"))
        assertTrue(text.contains(".heview"))
    }

    @Test
    fun `extraction overwrites an existing script so upgrades take effect`(@TempDir dir: Path) {
        val js = dir.resolve("claude-code/hooks/${HookInstaller.CLAUDE_JS}")
        Files.createDirectories(js.parent)
        Files.writeString(js, "// stale content from an older version")
        installer(dir).extractClaudeCodeScripts()
        assertTrue(Files.readString(js).startsWith("#!/usr/bin/env node"))
    }

    @Test
    fun `hasCli finds an executable on PATH and rejects a missing one`(@TempDir dir: Path) {
        val bin = Files.createDirectories(dir.resolve("bin"))
        val claude = Files.createFile(bin.resolve("claude"))
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwxr-xr-x"))
        val installer = installer(dir, pathEnv = "${bin}:/nonexistent/dir")
        assertTrue(installer.hasCli("claude"))
        assertFalse(installer.hasCli("codex")) // not present in the PATH dirs
    }

    @Test
    fun `hasCli ignores a non-executable file of the same name`(@TempDir dir: Path) {
        val bin = Files.createDirectories(dir.resolve("bin"))
        val claude = Files.createFile(bin.resolve("claude"))
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rw-r--r--")) // not +x
        assertFalse(installer(dir, pathEnv = bin.toString()).hasCli("claude"))
    }

    @Test
    fun `registerClaudeCode writes a bash UserPromptSubmit hook to a fresh settings file`(@TempDir dir: Path) {
        installer(dir).registerClaudeCode()
        val cmd = soleCommand(dir.resolve("dot-claude/settings.json"))
        assertTrue(cmd.startsWith("bash "))
        assertTrue(cmd.endsWith(HookInstaller.CLAUDE_SH))
    }

    @Test
    fun `registerClaudeCode preserves unrelated config and never double-registers`(@TempDir dir: Path) {
        val settings = dir.resolve("dot-claude/settings.json")
        Files.createDirectories(settings.parent)
        // A pre-existing unrelated key + a foreign tool's hook (e.g. reviewa) that must be preserved.
        Files.writeString(
            settings,
            """{"model":"opus","hooks":{"UserPromptSubmit":[{"hooks":[{"type":"command","command":"bash /other/tool.sh","timeout":5}]}]}}""",
        )
        val inst = installer(dir)
        inst.registerClaudeCode()
        inst.registerClaudeCode() // second call is a no-op

        val root = JsonParser.parseString(Files.readString(settings)).asJsonObject
        assertEquals("opus", root.get("model").asString) // unrelated key preserved
        assertEquals(2, userPromptSubmitSize(settings)) // foreign entry + exactly one heview entry
    }

    @Test
    fun `registerCodex enables codex_hooks and registers a python3 hook`(@TempDir dir: Path) {
        installer(dir).registerCodex()
        val toml = Files.readString(dir.resolve("dot-codex/config.toml"))
        assertTrue(toml.contains("[features]"))
        assertTrue(toml.contains("codex_hooks = true"))
        val cmd = soleCommand(dir.resolve("dot-codex/hooks.json"))
        assertTrue(cmd.startsWith("python3 "))
        assertTrue(cmd.endsWith(HookInstaller.CODEX_PY))
    }

    @Test
    fun `registerCodex appends a features section without disturbing other sections`(@TempDir dir: Path) {
        val toml = dir.resolve("dot-codex/config.toml")
        Files.createDirectories(toml.parent)
        Files.writeString(toml, "[model]\nname = \"gpt\"\n")
        installer(dir).registerCodex()
        val out = Files.readString(toml)
        assertTrue(out.contains("[model]") && out.contains("name = \"gpt\"")) // preserved
        assertTrue(out.contains("[features]") && out.contains("codex_hooks = true"))
    }

    @Test
    fun `registerCodex inserts codex_hooks into an existing features section`(@TempDir dir: Path) {
        val toml = dir.resolve("dot-codex/config.toml")
        Files.createDirectories(toml.parent)
        Files.writeString(toml, "[features]\nother_flag = true\n")
        installer(dir).registerCodex()
        val out = Files.readString(toml)
        assertTrue(out.contains("other_flag = true")) // preserved
        assertTrue(out.contains("codex_hooks = true"))
    }

    @Test
    fun `registerCodex warns and does not flip an explicit codex_hooks false`(@TempDir dir: Path) {
        val toml = dir.resolve("dot-codex/config.toml")
        Files.createDirectories(toml.parent)
        Files.writeString(toml, "[features]\ncodex_hooks = false\n")
        val warnings = mutableListOf<String>()
        installer(dir, warn = { warnings.add(it) }).registerCodex()
        assertEquals(1, warnings.size)
        assertTrue(Files.readString(toml).contains("codex_hooks = false")) // left as the user set it
    }

    @Test
    fun `installAll extracts and registers only for CLIs present on PATH`(@TempDir dir: Path) {
        val bin = Files.createDirectories(dir.resolve("bin"))
        val claude = Files.createFile(bin.resolve("claude"))
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwxr-xr-x"))
        // codex is deliberately absent from PATH
        installer(dir, pathEnv = bin.toString()).installAll()

        assertTrue(Files.exists(dir.resolve("claude-code/hooks/${HookInstaller.CLAUDE_JS}")))
        assertTrue(Files.exists(dir.resolve("dot-claude/settings.json")))
        assertFalse(Files.exists(dir.resolve("codex/hooks/${HookInstaller.CODEX_PY}")))
        assertFalse(Files.exists(dir.resolve("dot-codex/hooks.json")))
    }
}
