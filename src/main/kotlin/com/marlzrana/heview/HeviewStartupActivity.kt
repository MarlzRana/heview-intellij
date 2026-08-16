package com.marlzrana.heview

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.marlzrana.heview.hooks.HookInstaller
import com.marlzrana.heview.ui.CommentInlayManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On project open:
 * - boots the project's [CommentInlayManager] (editor + store listeners, pool hydration), and
 * - installs the coding-agent hooks once per application session.
 *
 * [CommentInlayManager.init] touches editors + the EDT-confined store, so it's dispatched to the EDT.
 * Hook installation is application-global work (edits `~/.claude` / `~/.codex`, and loading the shell
 * PATH via EnvironmentUtil can block), so it runs on an **application-pooled** thread — not this
 * project's coroutine — so project close can't cancel it and it isn't on the startup critical path.
 * It runs at most once per app, retries on the next project open after a failure, and is skipped in
 * unit-test / headless mode so tests can't touch the real `~/.claude` / `~/.codex`.
 */
internal class HeviewStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        thisLogger().info("heview: plugin loaded for project '${project.name}'")

        ApplicationManager.getApplication().invokeLater(
            { project.service<CommentInlayManager>().init() },
            project.disposed,
        )

        maybeInstallHooks()
    }

    private fun maybeInstallHooks() {
        val app = ApplicationManager.getApplication()
        if (!installationAllowed(app.isUnitTestMode, app.isHeadlessEnvironment)) return
        if (!HOOKS_INSTALLED.compareAndSet(false, true)) return
        app.executeOnPooledThread {
            val installed = try {
                HookInstaller(warn = ::notifyHookWarning).installAll()
            } catch (e: Exception) {
                thisLogger().warn("heview: hook installation failed", e)
                false
            }
            // installAll swallows per-agent failures (returning false); re-arm so a later project-open
            // retries after a transient failure, rather than leaving one agent unhooked all session.
            if (!installed) HOOKS_INSTALLED.set(false)
        }
    }

    private fun notifyHookWarning(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("heview")
            .createNotification(message, NotificationType.WARNING)
            .notify(null)
    }

    companion object {
        private val HOOKS_INSTALLED = AtomicBoolean(false)

        /** Pure guard (unit-tested): hooks must NEVER install in unit-test or headless mode. */
        fun installationAllowed(isUnitTestMode: Boolean, isHeadless: Boolean): Boolean =
            !isUnitTestMode && !isHeadless
    }
}
