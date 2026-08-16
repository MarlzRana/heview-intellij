package com.marlzrana.heview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.marlzrana.heview.ui.CommentInlayManager

/**
 * Leaves an inline review comment on the caret line by delegating to the project's
 * [CommentInlayManager], which owns the compose card, persists the
 * [com.marlzrana.heview.model.HeviewComment] to the shared pool, and tracks the resulting thread.
 *
 * Enabled only for files in the local file system, since `abs_path` must be a real OS path the
 * coding-agent hooks can prefix-match against their `cwd`.
 */
internal class AddCommentAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null &&
            file != null && file.isInLocalFileSystem &&
            e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        project.service<CommentInlayManager>().compose(editor)
    }
}
