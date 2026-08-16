package com.marlzrana.heview.hooks

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class HookInstallerTest {
    private fun installer(dir: Path, pathEnv: String = "") = HookInstaller(
        claudeCodeHooksDir = dir.resolve("claude-code/hooks"),
        codexHooksDir = dir.resolve("codex/hooks"),
        pathEnv = pathEnv,
    )

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
}
