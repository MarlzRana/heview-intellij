package com.marlzrana.heview.spike

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.marlzrana.heview.ui.ComponentInlayCardHost
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Phase 1 spike (TEMPORARY — delete once the real comment-thread UI lands).
 *
 * Proves the de-risked bet from plan.html §10: an interactive Swing card renders as a full-width
 * block inlay below the caret line via [ComponentInlayCardHost], stays put, and disposes cleanly
 * when its button is clicked. If this works in `runIde`, the `addComponentInlay` path is validated
 * on the 2024.2 baseline and the card UI can be built on top of [ComponentInlayCardHost].
 *
 * Trigger: editor right-click → "heview: Add Spike Inlay Card", or Ctrl+Alt+Shift+H.
 */
internal class AddSpikeCardAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val line = editor.caretModel.logicalPosition.line
        val lineEndOffset = editor.document.getLineEndOffset(line)

        val card = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = JBUI.Borders.empty(6, 10)
            add(JBLabel("heview inlay spike ✓  addComponentInlay on 242"))
        }
        val dismiss = JButton("Dismiss")
        card.add(dismiss)

        val inlay: Disposable = ComponentInlayCardHost(editor).addCardBelow(lineEndOffset, card) ?: return
        dismiss.addActionListener { Disposer.dispose(inlay) }
    }
}
