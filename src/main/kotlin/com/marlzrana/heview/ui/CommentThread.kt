package com.marlzrana.heview.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.marlzrana.heview.model.HeviewComment
import java.awt.BorderLayout
import java.awt.Component
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
        renderCompose()
        inlay = host.addCardBelow(lineEndOffset, panel) ?: return false
        return true
    }

    private fun dispose() {
        inlay?.let { Disposer.dispose(it) }
        inlay = null
    }

    private fun renderCompose() {
        val area = JBTextArea(3, 60).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        val submit = JButton("Submit").apply {
            addActionListener {
                val text = area.text.trim()
                if (text.isNotEmpty()) {
                    comment = onSubmit(text)
                    renderDisplay()
                }
            }
        }
        val cancel = JButton("Cancel").apply { addActionListener { dispose() } }
        setContent(area, buttonRow(cancel, submit))
        area.requestFocusInWindow()
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
