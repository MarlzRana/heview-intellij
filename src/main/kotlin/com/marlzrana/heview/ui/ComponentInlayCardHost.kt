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
    override fun addCardBelow(lineEndOffset: Int, card: JComponent): Disposable? = preserveScroll {
        editor.addComponentInlay(
            lineEndOffset,
            InlayProperties().relatesToPrecedingText(true),
            card,
            ComponentInlayAlignment.FIT_VIEWPORT_WIDTH,
        )
    }

    override fun disposeCard(card: Disposable) = preserveScroll {
        Disposer.dispose(card) // removing the inlay shrinks editor height
    }

    // Save/restore the viewport around a block-inlay add or remove so editor-height changes above the
    // fold don't jump the view. Restoring on the add-null path is harmless — no inlay, no height change.
    private inline fun <T> preserveScroll(block: () -> T): T {
        val keeper = EditorScrollingPositionKeeper(editor)
        try {
            keeper.savePosition()
            return block().also { keeper.restorePosition(false) }
        } finally {
            Disposer.dispose(keeper)
        }
    }
}
