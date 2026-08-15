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
 * Delete calls [onDelete] and removes the inlay. Replies / edit / re-pend and the consumption
 * watcher arrive in later increments.
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
) {
    private val panel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 10) }
    private var inlay: Disposable? = null
    private var comment: HeviewComment? = null

    /** Place the compose card below the target line. Returns false if the host could not place it. */
    fun startCompose(): Boolean {
        val input = renderCompose()
        inlay = host.addCardBelow(lineEndOffset, panel) ?: return false
        // The EditorTextField creates its editor only once shown, so focus after the inlay is placed
        // and realized (next EDT tick) — otherwise the user has to click the card before typing.
        ApplicationManager.getApplication().invokeLater {
            IdeFocusManager.getInstance(project).requestFocus(input, true)
        }
        return true
    }

    private fun dispose() {
        inlay?.let { Disposer.dispose(it) }
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
                val text = input.text.trim()
                if (text.isNotEmpty()) {
                    comment = onSubmit(text)
                    renderDisplay()
                }
            }
        }
        val cancel = JButton("Cancel").apply { addActionListener { dispose() } }
        setContent(input, buttonRow(cancel, submit))
        return input
    }

    private fun renderDisplay() {
        val current = comment ?: return
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JBLabel(author))
            add(JBLabel("Pending").apply { foreground = JBColor.ORANGE })
        }
        val body = JBTextArea(current.content).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
        }
        val delete = JButton("Delete").apply {
            addActionListener {
                onDelete(current)
                dispose()
            }
        }
        panel.removeAll()
        panel.add(header, BorderLayout.NORTH)
        panel.add(body, BorderLayout.CENTER)
        panel.add(buttonRow(delete), BorderLayout.SOUTH)
        reflow()
    }

    private fun buttonRow(vararg buttons: JButton): JPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { buttons.forEach { add(it) } }

    private fun setContent(center: Component, south: Component) {
        panel.removeAll()
        panel.add(center, BorderLayout.CENTER)
        panel.add(south, BorderLayout.SOUTH)
        reflow()
    }

    private fun reflow() {
        panel.revalidate()
        panel.repaint()
    }
}
