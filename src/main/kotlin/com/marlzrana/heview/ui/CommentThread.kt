package com.marlzrana.heview.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.marlzrana.heview.model.HeviewComment
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * A single comment thread rendered as an editor inlay card via [InlayCardHost].
 *
 * Phase 1 covers the create → display → delete loop: [startCompose] shows an empty compose card;
 * Submit calls [onSubmit] (which persists and returns the stored record) and flips to display mode;
 * Delete calls [onDelete] and removes the inlay; Cancel discards. [onDispose] is registered on the
 * inlay, so it runs on *any* teardown — Cancel/Delete or the platform disposing the inlay when the
 * editor closes — letting the caller release resources it owns (e.g. the line anchor). Replies /
 * edit / re-pend and the consumption watcher arrive in later increments.
 *
 * EDT-only — all methods run on the event dispatch thread that owns the editor.
 */
internal class CommentThread(
    private val project: Project,
    private val host: InlayCardHost,
    private val lineEndOffset: Int,
    private val author: String,
    private val onSubmit: (text: String) -> HeviewComment,
    private val onDelete: (comment: HeviewComment) -> Unit,
    private val onDispose: () -> Unit = {},
) {
    private val panel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 10) }
    private var inlay: Disposable? = null
    private var disposed = false

    /** Place the compose card below the target line. Returns false if the host could not place it. */
    fun startCompose(): Boolean {
        val input = renderCompose()
        val placed = host.addCardBelow(lineEndOffset, panel) ?: return false
        inlay = placed
        // Run onDispose on any teardown, including the platform disposing the inlay on editor close;
        // set `disposed` so queued UI callbacks (focus, delete-enable) become no-ops.
        Disposer.register(placed, Disposable { disposed = true; onDispose() })
        // The EditorTextField creates its editor only once shown, so focus after the inlay is placed
        // and realized (next EDT tick), guarded so we never focus a torn-down card or closed project.
        ApplicationManager.getApplication().invokeLater(
            { if (!disposed) IdeFocusManager.getInstance(project).requestFocus(input, true) },
            project.disposed,
        )
        return true
    }

    private fun dispose() {
        if (disposed) return
        disposed = true
        inlay?.let { Disposer.dispose(it) } // triggers the registered onDispose
        inlay = null
    }

    private fun renderCompose(): EditorTextField {
        // A real embedded editor (not a JBTextArea): it owns editor actions — Backspace/Enter/arrows/
        // undo edit the comment, instead of leaking to the underlying code editor.
        val input = EditorTextField("", project, PlainTextFileType.INSTANCE).apply {
            setOneLineMode(false)
            setPlaceholder("Leave a comment…")
            // Fixed initial height; BorderLayout.CENTER stretches the width to the inlay.
            preferredSize = Dimension(1, JBUI.scale(64))
            addSettingsProvider { it.settings.isUseSoftWraps = true }
        }
        val submit = JButton("Submit").apply {
            addActionListener {
                val text = input.text
                // Trim only decides emptiness; the stored content is verbatim (matches reviewa).
                if (text.isNotBlank()) renderDisplay(onSubmit(text))
            }
        }
        val cancel = JButton("Cancel").apply { addActionListener { dispose() } }
        setContent(center = input, south = buttonRow(cancel, submit))
        return input
    }

    private fun renderDisplay(current: HeviewComment) {
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JBLabel(author))
            add(JBLabel("Pending").apply { foreground = JBColor.ORANGE })
        }
        val body = JBTextArea(current.content).apply {
            isEditable = false
            // Non-focusable so clicks don't route Backspace/Enter/arrows to the host code editor.
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
        }
        val delete = JButton("Delete").apply {
            // Start disabled so a double-click on Submit doesn't land on the relocated Delete button;
            // re-enabled after the current event burst.
            isEnabled = false
            addActionListener {
                onDelete(current)
                dispose()
            }
        }
        setContent(center = body, south = buttonRow(delete), north = header)
        ApplicationManager.getApplication().invokeLater(
            { if (!disposed) delete.isEnabled = true },
            project.disposed,
        )
    }

    private fun buttonRow(vararg buttons: JButton): JPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { buttons.forEach { add(it) } }

    private fun setContent(center: Component, south: Component, north: Component? = null) {
        panel.removeAll()
        if (north != null) panel.add(north, BorderLayout.NORTH)
        panel.add(center, BorderLayout.CENTER)
        panel.add(south, BorderLayout.SOUTH)
        panel.revalidate()
        panel.repaint()
    }
}
