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
    fun `registerClaudeCode writes a shell-quoted bash UserPromptSubmit hook to a fresh settings file`(@TempDir dir: Path) {
        installer(dir).registerClaudeCode()
        val cmd = soleCommand(dir.resolve("dot-claude/settings.json"))
        assertTrue(cmd.startsWith("bash '")) // path shell-quoted
        assertTrue(cmd.contains(HookInstaller.CLAUDE_SH))
    }

    @Test
    fun `registered command shell-quotes a hooks path containing spaces`(@TempDir dir: Path) {
        val spaced = dir.resolve("Jane Doe/.heview") // home dir with a space
        HookInstaller(
            claudeCodeHooksDir = spaced.resolve("claude-code/hooks"),
            codexHooksDir = spaced.resolve("codex/hooks"),
            claudeSettings = dir.resolve("dot-claude/settings.json"),
            codexConfigToml = dir.resolve("dot-codex/config.toml"),
            codexHooksJson = dir.resolve("dot-codex/hooks.json"),
            pathEnv = "",
        ).registerClaudeCode()
        val cmd = soleCommand(dir.resolve("dot-claude/settings.json"))
        assertTrue(cmd.contains("'${spaced}/claude-code/hooks/${HookInstaller.CLAUDE_SH}'")) // whole path quoted
    }

    @Test
    fun `registerClaudeCode leaves a malformed settings file untouched and skips registration`(@TempDir dir: Path) {
        val settings = dir.resolve("dot-claude/settings.json")
        Files.createDirectories(settings.parent)
        val original = """{"model":"opus",""" // truncated / invalid JSON
        Files.writeString(settings, original)
        val warnings = mutableListOf<String>()
        installer(dir, warn = { warnings.add(it) }).registerClaudeCode()
        assertEquals(original, Files.readString(settings)) // NOT clobbered
        assertEquals(1, warnings.size)
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
    fun `registerCodex enables the canonical hooks flag and registers a python3 hook, idempotently`(@TempDir dir: Path) {
        val inst = installer(dir)
        inst.registerCodex()
        inst.registerCodex() // second call must not duplicate anything
        val toml = Files.readString(dir.resolve("dot-codex/config.toml"))
        assertTrue(toml.contains("[features]"))
        assertTrue(Regex("(?m)^hooks = true$").containsMatchIn(toml)) // canonical key, not codex_hooks
        assertFalse(toml.contains("codex_hooks")) // deprecated alias is not written
        assertEquals(1, Regex("\\[features\\]").findAll(toml).count()) // no duplicate section
        assertEquals(1, Regex("(?m)^\\s*hooks\\s*=").findAll(toml).count()) // no duplicate flag
        val hooksJson = dir.resolve("dot-codex/hooks.json")
        assertEquals(1, userPromptSubmitSize(hooksJson)) // no double-register
        val cmd = soleCommand(hooksJson)
        assertTrue(cmd.startsWith("python3 '"))
        assertTrue(cmd.contains(HookInstaller.CODEX_PY))
    }

    @Test
    fun `registerCodex preserves an unrelated hooks_json entry`(@TempDir dir: Path) {
        val hooksJson = dir.resolve("dot-codex/hooks.json")
        Files.createDirectories(hooksJson.parent)
        Files.writeString(
            hooksJson,
            """{"hooks":{"UserPromptSubmit":[{"hooks":[{"type":"command","command":"python3 /other/x.py","timeout":5}]}]}}""",
        )
        installer(dir).registerCodex()
        assertEquals(2, userPromptSubmitSize(hooksJson)) // foreign entry + one heview entry
    }

    @Test
    fun `registerCodex appends a features section without disturbing other sections`(@TempDir dir: Path) {
        val toml = dir.resolve("dot-codex/config.toml")
        Files.createDirectories(toml.parent)
        Files.writeString(toml, "[model]\nname = \"gpt\"\n")
        installer(dir).registerCodex()
        val out = Files.readString(toml)
        assertTrue(out.contains("[model]") && out.contains("name = \"gpt\"")) // preserved
        assertTrue(out.contains("[features]") && Regex("(?m)^hooks = true$").containsMatchIn(out))
    }

    @Test
    fun `registerCodex inserts the hooks flag into an existing features section`(@TempDir dir: Path) {
        val toml = dir.resolve("dot-codex/config.toml")
        Files.createDirectories(toml.parent)
        Files.writeString(toml, "[features]\nother_flag = true\n")
        installer(dir).registerCodex()
        val out = Files.readString(toml)
        assertTrue(out.contains("other_flag = true")) // preserved
        assertTrue(Regex("(?m)^hooks = true$").containsMatchIn(out))
    }

    @Test
    fun `registerCodex warns and does not flip an explicit hooks false (and the deprecated codex_hooks false)`(@TempDir dir: Path) {
        for (line in listOf("hooks = false", "codex_hooks = false")) {
            val toml = dir.resolve("dot-codex/config.toml")
            Files.createDirectories(toml.parent)
            Files.writeString(toml, "[features]\n$line\n")
            val warnings = mutableListOf<String>()
            installer(dir, warn = { warnings.add(it) }).registerCodex()
            assertEquals(1, warnings.size, "expected a warning for $line")
            assertTrue(Files.readString(toml).contains(line)) // left as the user set it
            Files.deleteIfExists(dir.resolve("dot-codex/hooks.json")) // reset for the next iteration
        }
    }

    @Test
    fun `registerCodex refuses to append when features exists only as an inline table`(@TempDir dir: Path) {
        val toml = dir.resolve("dot-codex/config.toml")
        Files.createDirectories(toml.parent)
        val original = "features = { other = 1 }\n"
        Files.writeString(toml, original)
        val warnings = mutableListOf<String>()
        installer(dir, warn = { warnings.add(it) }).registerCodex()
        assertEquals(original, Files.readString(toml)) // not corrupted with a duplicate [features] table
        assertEquals(1, warnings.size)
    }

    @Test
    fun `installAll extracts all scripts but registers only CLIs present on PATH`(@TempDir dir: Path) {
        val bin = Files.createDirectories(dir.resolve("bin"))
        val claude = Files.createFile(bin.resolve("claude"))
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwxr-xr-x"))
        // codex is deliberately absent from PATH
        installer(dir, pathEnv = bin.toString()).installAll()

        assertTrue(Files.exists(dir.resolve("claude-code/hooks/${HookInstaller.CLAUDE_JS}")))
        assertTrue(Files.exists(dir.resolve("dot-claude/settings.json"))) // claude on PATH → registered
        assertTrue(Files.exists(dir.resolve("codex/hooks/${HookInstaller.CODEX_PY}"))) // extracted regardless
        assertFalse(Files.exists(dir.resolve("dot-codex/hooks.json"))) // codex absent → NOT registered
    }

    @Test
    fun `registerCodex inserts the hooks flag in features, not fooled by a false flag in another section`(@TempDir dir: Path) {
        val toml = dir.resolve("dot-codex/config.toml")
        Files.createDirectories(toml.parent)
        Files.writeString(toml, "[features]\nother = 1\n\n[sandbox]\nhooks = false\n")
        val warnings = mutableListOf<String>()
        installer(dir, warn = { warnings.add(it) }).registerCodex()
        val out = Files.readString(toml)
        assertTrue(Regex("(?m)^hooks = true$").containsMatchIn(out)) // inserted into [features]
        assertTrue(out.contains("[sandbox]\nhooks = false")) // the other section is untouched
        assertEquals(0, warnings.size) // the [sandbox] false must not trigger the warning
    }

    @Test
    fun `registerCodex edits a whitespaced features header without appending a duplicate`(@TempDir dir: Path) {
        val toml = dir.resolve("dot-codex/config.toml")
        Files.createDirectories(toml.parent)
        Files.writeString(toml, "[ features ]\nother = 1\n")
        installer(dir).registerCodex()
        val out = Files.readString(toml)
        assertEquals(1, Regex("(?m)^\\s*\\[\\s*features\\s*\\]").findAll(out).count()) // no duplicate table
        assertTrue(Regex("(?m)^hooks = true$").containsMatchIn(out))
    }

    @Test
    fun `registration leaves a non-object config untouched`(@TempDir dir: Path) {
        val settings = dir.resolve("dot-claude/settings.json")
        Files.createDirectories(settings.parent)
        val original = "[]" // valid JSON, but the root is an array, not an object
        Files.writeString(settings, original)
        val warnings = mutableListOf<String>()
        installer(dir, warn = { warnings.add(it) }).registerClaudeCode()
        assertEquals(original, Files.readString(settings)) // not clobbered
        assertEquals(1, warnings.size)
    }

    @Test
    fun `registration leaves a config whose hooks value is the wrong type untouched`(@TempDir dir: Path) {
        val settings = dir.resolve("dot-claude/settings.json")
        Files.createDirectories(settings.parent)
        val original = """{"hooks":"nope"}""" // hooks present but a string, not an object
        Files.writeString(settings, original)
        val warnings = mutableListOf<String>()
        installer(dir, warn = { warnings.add(it) }).registerClaudeCode()
        assertEquals(original, Files.readString(settings)) // user's hooks value preserved
        assertEquals(1, warnings.size)
    }

    @Test
    fun `registerClaudeCode preserves a sibling PreToolUse hook event`(@TempDir dir: Path) {
        val settings = dir.resolve("dot-claude/settings.json")
        Files.createDirectories(settings.parent)
        Files.writeString(
            settings,
            """{"hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"bash /x.sh"}]}]}}""",
        )
        installer(dir).registerClaudeCode()
        val hooks = JsonParser.parseString(Files.readString(settings)).asJsonObject.getAsJsonObject("hooks")
        assertTrue(hooks.has("PreToolUse")) // sibling event preserved
        assertEquals(1, hooks.getAsJsonArray("UserPromptSubmit").size())
    }

    @Test
    fun `registerClaudeCode writes a well-formed hook object with type command and a timeout`(@TempDir dir: Path) {
        installer(dir).registerClaudeCode()
        val root = JsonParser.parseString(Files.readString(dir.resolve("dot-claude/settings.json"))).asJsonObject
        val hook = root.getAsJsonObject("hooks").getAsJsonArray("UserPromptSubmit")[0]
            .asJsonObject.getAsJsonArray("hooks")[0].asJsonObject
        assertEquals("command", hook.get("type").asString)
        assertEquals(10, hook.get("timeout").asInt)
    }

    @Test
    fun `writeAtomically preserves a symlinked settings file`(@TempDir dir: Path) {
        val real = dir.resolve("store/settings.json")
        Files.createDirectories(real.parent)
        Files.writeString(real, """{"model":"opus"}""")
        val link = dir.resolve("dot-claude/settings.json")
        Files.createDirectories(link.parent)
        Files.createSymbolicLink(link, real)

        installer(dir).registerClaudeCode()

        assertTrue(Files.isSymbolicLink(link)) // link preserved, not replaced by a regular file
        val root = JsonParser.parseString(Files.readString(real)).asJsonObject // written through the link
        assertEquals("opus", root.get("model").asString) // unrelated content kept
        assertEquals(1, root.getAsJsonObject("hooks").getAsJsonArray("UserPromptSubmit").size())
    }
}
