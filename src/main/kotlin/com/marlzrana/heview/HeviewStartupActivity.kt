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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On project open:
 * - boots the project's [CommentInlayManager] (editor + store listeners, pool hydration), and
 * - installs the coding-agent hooks once per application session.
 *
 * [ProjectActivity.execute] runs on a background coroutine. The manager touches editors and the
 * EDT-confined store, so [CommentInlayManager.init] is dispatched to the EDT. Hook installation is
 * file I/O against the agents' global config, so it runs here on the background coroutine (off the
 * EDT), guarded to run at most once per app and never in unit-test / headless mode so tests can't
 * touch the real `~/.claude` / `~/.codex`.
 */
internal class HeviewStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        thisLogger().info("heview: plugin loaded for project '${project.name}'")

        ApplicationManager.getApplication().invokeLater(
            { project.service<CommentInlayManager>().init() },
            project.disposed,
        )

        installHooksOncePerApp()
    }

    private suspend fun installHooksOncePerApp() {
        val app = ApplicationManager.getApplication()
        if (app.isUnitTestMode || app.isHeadlessEnvironment) return
        if (!HOOKS_INSTALLED.compareAndSet(false, true)) return
        // PATH scan + config reads/writes are blocking I/O → the IO dispatcher, not the startup default.
        withContext(Dispatchers.IO) {
            try {
                HookInstaller(warn = ::notifyHookWarning).installAll()
            } catch (e: Exception) {
                thisLogger().warn("heview: hook installation failed", e)
            }
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
    }
}
