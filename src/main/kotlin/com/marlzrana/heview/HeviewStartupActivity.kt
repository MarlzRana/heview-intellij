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
import com.marlzrana.heview.watch.CommentsPoolWatcher
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On project open:
 * - boots the project's [CommentInlayManager] (editor + store listeners, pool hydration),
 * - starts the application [CommentsPoolWatcher] (marks consumed comments *Seen*), and
 * - installs the coding-agent hooks once per application session.
 *
 * [CommentInlayManager.init] touches editors + the EDT-confined store, so it's dispatched to the EDT.
 * The watcher (creates a NIO WatchService + directories) and hook installation (edits `~/.claude` /
 * `~/.codex`; loading the shell PATH via EnvironmentUtil can block) are application-global blocking
 * work, so they run on an **application-pooled** thread — not this project's coroutine — so project
 * close can't cancel them and they're off the startup critical path. The watcher's own once-guard and
 * the hook installer's once/retry flag dedup across repeated project opens; both are skipped in
 * unit-test / headless mode so tests never spin a real watcher thread or touch `~/.claude` / `~/.codex`.
 */
internal class HeviewStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        thisLogger().info("heview: plugin loaded for project '${project.name}'")

        ApplicationManager.getApplication().invokeLater(
            { project.service<CommentInlayManager>().init() },
            project.disposed,
        )

        maybeStartAppSideEffects()
    }

    private fun maybeStartAppSideEffects() {
        val app = ApplicationManager.getApplication()
        if (!installationAllowed(app.isUnitTestMode, app.isHeadlessEnvironment)) return
        app.executeOnPooledThread {
            try {
                // Idempotent: only the first call per app actually starts the watch thread.
                service<CommentsPoolWatcher>().ensureStarted()
            } catch (e: Exception) {
                thisLogger().warn("heview: failed to start the comments watcher", e)
            }
            installHooksOnce()
        }
    }

    private fun installHooksOnce() {
        if (!HOOKS_INSTALLED.compareAndSet(false, true)) return
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
