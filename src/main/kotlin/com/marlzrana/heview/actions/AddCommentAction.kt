package com.marlzrana.heview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.TextRange
import com.marlzrana.heview.model.newFileComment
import com.marlzrana.heview.storage.CommentStore
import com.marlzrana.heview.ui.CommentThread
import com.marlzrana.heview.ui.ComponentInlayCardHost
import com.marlzrana.heview.util.HeviewTime

/**
 * Leaves an inline review comment on the caret line: opens a compose card as an editor inlay and,
 * on submit, persists a [com.marlzrana.heview.model.HeviewComment] to the shared pool via the
 * [CommentStore] service.
 *
 * Enabled only for files in the local file system, since `abs_path` must be a real OS path the
 * coding-agent hooks can prefix-match against their `cwd`. The commented line is tracked with a
 * [com.intellij.openapi.editor.RangeMarker] and read at *submit* time, so edits made while the
 * compose card is open don't leave a stale `line_number`/`line_content`. `workspace` is the project
 * base path — a v1 approximation of reviewa's git-repo root (plan.html).
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
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (!file.isInLocalFileSystem) return
        val workspace = project.basePath ?: return
        val absPath = file.toNioPath().toString()

        val document = editor.document
        // The caret can sit in virtual space (past the last line); clamp so getLineEndOffset is safe.
        val line = editor.caretModel.logicalPosition.line
            .coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
        val lineEndOffset = document.getLineEndOffset(line)
        // Track the anchor line across edits made while composing; read it again at submit.
        val anchor = document.createRangeMarker(document.getLineStartOffset(line), lineEndOffset)

        val store = service<CommentStore>()
        val author = System.getProperty("user.name") ?: "You"

        val thread = CommentThread(
            project = project,
            host = ComponentInlayCardHost(editor),
            lineEndOffset = lineEndOffset,
            author = author,
            onSubmit = { text ->
                // Read the anchor only while valid; if its line was deleted, fall back to the
                // original line, clamped into range.
                val rawLine = if (anchor.isValid) document.getLineNumber(anchor.startOffset) else line
                val anchorLine = rawLine.coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
                val lineContent = document.getText(
                    TextRange(document.getLineStartOffset(anchorLine), document.getLineEndOffset(anchorLine)),
                )
                newFileComment(
                    workspace = workspace,
                    absPath = absPath,
                    line0Based = anchorLine,
                    lineContent = lineContent,
                    content = text,
                    createdAt = HeviewTime.nowIso(),
                ).also(store::save)
            },
            onDelete = { comment -> store.delete(comment.uuid) },
            onDispose = { if (anchor.isValid) anchor.dispose() },
        )

        if (!thread.startCompose()) {
            // No inlay was placed, so onDispose never runs — release the anchor here.
            if (anchor.isValid) anchor.dispose()
            thisLogger().warn("heview: inline comments are not available in this editor")
        }
    }
}
