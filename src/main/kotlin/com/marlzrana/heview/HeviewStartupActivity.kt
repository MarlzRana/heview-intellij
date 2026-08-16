package com.marlzrana.heview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.marlzrana.heview.ui.CommentInlayManager

/**
 * Boots the project's [CommentInlayManager]: wires the editor + store listeners and hydrates the
 * shared comment pool so existing comments reappear as inlays on the files they belong to.
 *
 * [ProjectActivity.execute] runs on a background coroutine, but the manager touches editors and the
 * EDT-confined store, so [CommentInlayManager.init] is dispatched to the EDT (and expires if the
 * project closes first).
 */
internal class HeviewStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        thisLogger().info("heview: plugin loaded for project '${project.name}'")
        ApplicationManager.getApplication().invokeLater(
            { project.service<CommentInlayManager>().init() },
            project.disposed,
        )
    }
}
