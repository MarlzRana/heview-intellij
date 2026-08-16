package com.marlzrana.heview.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.ComponentInlayAlignment
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.addComponentInlay
import com.intellij.openapi.editor.ex.util.EditorScrollingPositionKeeper
import com.intellij.openapi.util.Disposer
import javax.swing.JComponent

/**
 * [InlayCardHost] backed by `Editor.addComponentInlay`, verified present on the 2024.2 baseline.
 *
 * The returned `Inlay` is itself a [Disposable]; disposing it removes the inlay and disposes the
 * embedded Swing subtree. `relatesToPrecedingText(true)` keeps the card anchored to the line above
 * it — the commented line — as edits shift offsets. `FIT_VIEWPORT_WIDTH` makes the card span the
 * editor width so it reads as a review thread rather than a floating widget. Insertion is wrapped in
 * an `EditorScrollingPositionKeeper` so adding the block inlay doesn't jump the viewport.
 */
internal class ComponentInlayCardHost(private val editor: Editor) : InlayCardHost {
    override fun addCardBelow(lineEndOffset: Int, card: JComponent): Disposable? {
        val properties = InlayProperties().relatesToPrecedingText(true)
        val keeper = EditorScrollingPositionKeeper(editor)
        try {
            keeper.savePosition()
            val inlay = editor.addComponentInlay(
                lineEndOffset,
                properties,
                card,
                ComponentInlayAlignment.FIT_VIEWPORT_WIDTH,
            ) ?: return null
            keeper.restorePosition(false)
            return inlay
        } finally {
            Disposer.dispose(keeper)
        }
    }
}
