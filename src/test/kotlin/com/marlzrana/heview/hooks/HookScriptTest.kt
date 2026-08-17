package com.marlzrana.heview.hooks

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Behavioral coverage for the bundled injector scripts — the load-bearing contract that the installer
 * tests can't see: actually run node / python3 against a seeded `~/.heview/comments` pool and assert the
 * injected `additionalContext`, clean backticks, cwd directory-boundary matching, consumer routing, and
 * single-use consumption. Consumption is a **claim-by-move**: a consumed file is atomically moved into
 * `comments/consumed/` (the intent signal the watcher reads), not unlinked. Skips gracefully where
 * node / python3 aren't installed.
 */
class HookScriptTest {
    private fun toolAvailable(vararg cmd: String): Boolean =
        try {
            ProcessBuilder(*cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor(20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }

    /** Extract the scripts into a temp $HOME so the scripts' `~/.heview/comments` resolves to our pool. */
    private fun setupHome(dir: Path): Path {
        val home = Files.createDirectories(dir.resolve("home"))
        HookInstaller(
            claudeCodeHooksDir = home.resolve(".heview/claude-code/hooks"),
            codexHooksDir = home.resolve(".heview/codex/hooks"),
            claudeSettings = dir.resolve("unused-settings.json"),
            codexConfigToml = dir.resolve("unused.toml"),
            codexHooksJson = dir.resolve("unused-hooks.json"),
            pathEnv = "",
        ).apply { extractClaudeCodeScripts(); extractCodexScripts() }
        Files.createDirectories(home.resolve(".heview/comments"))
        return home
    }

    private fun seed(home: Path, uuid: String, absPath: String, line: Int, lineContent: String, content: String, createdAt: String = "2026-01-01T00:00:00.000Z", consumer: String? = null, logicalAbsPath: String = absPath) {
        val consumerField = if (consumer == null) "" else ",\"intended_consumer\":\"$consumer\""
        Files.writeString(
            home.resolve(".heview/comments/$uuid.json"),
            """{"uuid":"$uuid","status":"pending","created_at":"$createdAt","workspace":"/tmp/proj","abs_path":"$absPath","logical_abs_path":"$logicalAbsPath","line_number":$line,"line_content":"$lineContent","side":"file","content":"$content"$consumerField}""",
        )
    }

    private fun claudeCmd(home: Path) =
        listOf("bash", home.resolve(".heview/claude-code/hooks/${HookInstaller.CLAUDE_SH}").toString())

    private fun codexCmd(home: Path) =
        listOf("python3", home.resolve(".heview/codex/hooks/${HookInstaller.CODEX_PY}").toString())

    /** Run a hook with the given $HOME + cwd on stdin; return its raw stdout. */
    private fun run(cmd: List<String>, home: Path, cwd: String): String {
        val p = ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { it.environment()["HOME"] = home.toString() }
            .start()
        p.outputStream.bufferedWriter().use { it.write("""{"cwd":"$cwd"}""") }
        val out = p.inputStream.readBytes().decodeToString()
        p.waitFor(30, TimeUnit.SECONDS)
        return out
    }

    private fun pending(home: Path, uuid: String) = Files.exists(home.resolve(".heview/comments/$uuid.json"))

    private fun consumed(home: Path, uuid: String) =
        Files.exists(home.resolve(".heview/comments/consumed/$uuid.json"))

    /** The `status` field inside the consumed tombstone (the hook rewrites it to "processed"). */
    private fun consumedStatus(home: Path, uuid: String): String? {
        val f = home.resolve(".heview/comments/consumed/$uuid.json")
        if (!Files.exists(f)) return null
        return JsonParser.parseString(Files.readString(f)).asJsonObject.get("status")?.asString
    }

    private fun consumedRaw(home: Path, uuid: String): String =
        Files.readString(home.resolve(".heview/comments/consumed/$uuid.json"))

    private fun additionalContext(stdout: String): String? {
        if (stdout.isBlank()) return null
        return JsonParser.parseString(stdout).asJsonObject
            .getAsJsonObject("hookSpecificOutput").get("additionalContext").asString
    }

    @Test
    fun `claude and codex inject the identical clean-backtick block and claim the file into consumed`(@TempDir dir: Path) {
        assumeTrue(toolAvailable("node", "--version") && toolAvailable("python3", "--version"))
        val expected = "In `sub/Foo.kt` at line 3:\n```\nval x = 1\n```\nmake it const"

        val h1 = setupHome(dir.resolve("a"))
        seed(h1, "c1", "/tmp/proj/sub/Foo.kt", 3, "val x = 1", "make it const")
        val claudeOut = additionalContext(run(claudeCmd(h1), h1, "/tmp/proj"))
        assertEquals(expected, claudeOut)
        assertFalse(claudeOut!!.contains("\\`")) // clean backticks, no literal backslash
        assertFalse(pending(h1, "c1")) // left the pending pool…
        assertTrue(consumed(h1, "c1")) // …by moving into consumed/ (single-use claim)
        assertEquals("processed", consumedStatus(h1, "c1")) // tombstone rewritten to Seen

        val h2 = setupHome(dir.resolve("b"))
        seed(h2, "c1", "/tmp/proj/sub/Foo.kt", 3, "val x = 1", "make it const")
        val codexOut = additionalContext(run(codexCmd(h2), h2, "/tmp/proj"))
        assertEquals(expected, codexOut) // byte-identical to Claude
        assertFalse(pending(h2, "c1"))
        assertTrue(consumed(h2, "c1"))
        assertEquals("processed", consumedStatus(h2, "c1"))

        // Tombstone is pretty-printed (one property per line, like an IDE-written comment) and the two
        // injectors produce byte-identical output.
        assertTrue(consumedRaw(h1, "c1").contains("\n  \"uuid\""))
        assertEquals(consumedRaw(h1, "c1"), consumedRaw(h2, "c1"))
    }

    @Test
    fun `a second run does not re-inject an already-consumed comment`(@TempDir dir: Path) {
        assumeTrue(toolAvailable("node", "--version") && toolAvailable("python3", "--version"))
        val agents = listOf<Pair<String, (Path) -> List<String>>>("claude" to { claudeCmd(it) }, "codex" to { codexCmd(it) })
        for ((name, cmd) in agents) {
            val home = setupHome(dir.resolve("rerun-$name"))
            seed(home, "c1", "/tmp/proj/f.kt", 1, "x", "note")
            assertNotNull(additionalContext(run(cmd(home), home, "/tmp/proj"))) // injected once
            assertTrue(consumed(home, "c1"))                                    // claimed into consumed/
            // The claimed file lives under consumed/, which the pool listing (*.json only) skips, so a
            // second UserPromptSubmit finds nothing to inject — single-use holds across invocations.
            assertNull(additionalContext(run(cmd(home), home, "/tmp/proj")))
        }
    }

    @Test
    fun `a sibling directory sharing a name prefix is not matched or consumed`(@TempDir dir: Path) {
        assumeTrue(toolAvailable("node", "--version") && toolAvailable("python3", "--version"))
        val agents = listOf<Pair<String, (Path) -> List<String>>>(
            "claude" to { claudeCmd(it) },
            "codex" to { codexCmd(it) },
        )
        for ((name, cmd) in agents) {
            val home = setupHome(dir.resolve("sib-$name"))
            // cwd /tmp/proj must NOT capture /tmp/proj-backend
            seed(home, "s1", "/tmp/proj-backend/x.kt", 1, "code", "note")
            val out = additionalContext(run(cmd(home), home, "/tmp/proj"))
            assertNull(out) // not injected
            assertTrue(Files.exists(home.resolve(".heview/comments/s1.json"))) // NOT stolen/deleted
        }
    }

    @Test
    fun `intended_consumer routes comments to the right agent only`(@TempDir dir: Path) {
        assumeTrue(toolAvailable("node", "--version") && toolAvailable("python3", "--version"))
        // Routed to codex → Claude must skip it (and leave it for codex).
        val hc = setupHome(dir.resolve("c"))
        seed(hc, "r1", "/tmp/proj/f.kt", 1, "x", "for codex", consumer = "codex")
        assertNull(additionalContext(run(claudeCmd(hc), hc, "/tmp/proj")))
        assertTrue(Files.exists(hc.resolve(".heview/comments/r1.json")))

        // Routed to claude_code → Codex must skip it.
        val hx = setupHome(dir.resolve("x"))
        seed(hx, "r2", "/tmp/proj/f.kt", 1, "x", "for claude", consumer = "claude_code")
        assertNull(additionalContext(run(codexCmd(hx), hx, "/tmp/proj")))
        assertTrue(Files.exists(hx.resolve(".heview/comments/r2.json")))
    }

    @Test
    fun `matched comments are ordered by created_at`(@TempDir dir: Path) {
        assumeTrue(toolAvailable("node", "--version"))
        val home = setupHome(dir)
        seed(home, "late", "/tmp/proj/b.kt", 2, "b", "second", createdAt = "2026-02-01T00:00:00.000Z")
        seed(home, "early", "/tmp/proj/a.kt", 1, "a", "first", createdAt = "2026-01-01T00:00:00.000Z")
        val out = additionalContext(run(claudeCmd(home), home, "/tmp/proj"))!!
        assertTrue(out.indexOf("first") < out.indexOf("second")) // created_at ascending
    }

    @Test
    fun `a cwd with a trailing slash still matches and consumes`(@TempDir dir: Path) {
        assumeTrue(toolAvailable("node", "--version") && toolAvailable("python3", "--version"))
        val agents = listOf<Pair<String, (Path) -> List<String>>>("claude" to { claudeCmd(it) }, "codex" to { codexCmd(it) })
        for ((name, cmd) in agents) {
            val home = setupHome(dir.resolve("ts-$name"))
            seed(home, "t1", "/tmp/proj/f.kt", 1, "x", "note")
            val out = additionalContext(run(cmd(home), home, "/tmp/proj/")) // note the trailing slash
            assertNotNull(out)
            assertFalse(pending(home, "t1")) // matched + consumed
            assertTrue(consumed(home, "t1")) // claimed into consumed/
        }
    }

    @Test
    fun `a corrupt pool file is skipped while a valid comment is still injected`(@TempDir dir: Path) {
        assumeTrue(toolAvailable("node", "--version") && toolAvailable("python3", "--version"))
        val agents = listOf<Pair<String, (Path) -> List<String>>>("claude" to { claudeCmd(it) }, "codex" to { codexCmd(it) })
        for ((name, cmd) in agents) {
            val home = setupHome(dir.resolve("cp-$name"))
            Files.writeString(home.resolve(".heview/comments/bad.json"), "{ not json")
            seed(home, "ok", "/tmp/proj/f.kt", 1, "x", "note")
            val out = additionalContext(run(cmd(home), home, "/tmp/proj"))
            assertEquals("In `f.kt` at line 1:\n```\nx\n```\nnote", out) // valid one still injected
            assertFalse(pending(home, "ok")) // valid consumed…
            assertTrue(consumed(home, "ok")) // …by moving into consumed/
            assertTrue(Files.exists(home.resolve(".heview/comments/bad.json"))) // corrupt left alone
        }
    }

    @Test
    fun `claude matches logical_abs_path while codex matches only abs_path`(@TempDir dir: Path) {
        assumeTrue(toolAvailable("node", "--version") && toolAvailable("python3", "--version"))
        // abs_path is OUTSIDE cwd; logical_abs_path is INSIDE.
        val hc = setupHome(dir.resolve("lp-claude"))
        seed(hc, "l1", "/tmp/other/x.kt", 5, "code", "note", logicalAbsPath = "/tmp/proj/x.kt")
        val claudeOut = additionalContext(run(claudeCmd(hc), hc, "/tmp/proj"))
        assertNotNull(claudeOut) // Claude matches via logical_abs_path
        assertTrue(claudeOut!!.contains("/tmp/other/x.kt")) // display uses abs_path (absolute, since outside cwd)
        assertFalse(pending(hc, "l1")) // consumed by Claude…
        assertTrue(consumed(hc, "l1")) // …moved into consumed/

        val hx = setupHome(dir.resolve("lp-codex"))
        seed(hx, "l1", "/tmp/other/x.kt", 5, "code", "note", logicalAbsPath = "/tmp/proj/x.kt")
        assertNull(additionalContext(run(codexCmd(hx), hx, "/tmp/proj"))) // Codex matches abs_path only
        assertTrue(pending(hx, "l1")) // NOT consumed by Codex
    }
}
