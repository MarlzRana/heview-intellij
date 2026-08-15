package com.marlzrana.heview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.marlzrana.heview.model.CommentSide
import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.model.HeviewComment
import com.marlzrana.heview.storage.CommentStore
import com.marlzrana.heview.ui.CommentThread
import com.marlzrana.heview.ui.ComponentInlayCardHost
import java.time.Instant
import java.util.UUID

/**
 * Leaves an inline review comment on the caret line: opens a compose card as an editor inlay and,
 * on submit, persists a [HeviewComment] to the shared pool via the [CommentStore] service.
 *
 * v1 comments carry `side = FILE` (no diff-side detection yet) and `logical_abs_path == abs_path`
 * (no plan remapping yet). `workspace` is the project base path — a v1 simplification of reviewa's
 * git-repo-root; the coding-agent hooks match by `cwd` prefix, so the project base is sufficient.
 */
internal class AddCommentAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null &&
            e.getData(CommonDataKeys.VIRTUAL_FILE) != null &&
            e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: FileDocumentManager.getInstance().getFile(editor.document)
            ?: return
        val workspace = project.basePath ?: return
        val absPath = file.path

        val document = editor.document
        val line = editor.caretModel.logicalPosition.line
        val lineNumber = line + 1
        val lineContent = document.getText(
            TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)),
        )
        val lineEndOffset = document.getLineEndOffset(line)

        val store = service<CommentStore>()
        val author = System.getProperty("user.name") ?: "You"

        val thread = CommentThread(
            project = project,
            host = ComponentInlayCardHost(editor),
            lineEndOffset = lineEndOffset,
            author = author,
            onSubmit = { text ->
                HeviewComment(
                    uuid = UUID.randomUUID().toString(),
                    status = CommentStatus.PENDING,
                    createdAt = Instant.now().toString(),
                    workspace = workspace,
                    absPath = absPath,
                    logicalAbsPath = absPath,
                    lineNumber = lineNumber,
                    lineContent = lineContent,
                    side = CommentSide.FILE,
                    content = text,
                ).also(store::save)
            },
            onDelete = { comment -> store.delete(comment.uuid) },
        )
        thread.startCompose()
    }
}
