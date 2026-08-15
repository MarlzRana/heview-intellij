package com.marlzrana.heview.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.ComponentInlayAlignment
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.addComponentInlay
import javax.swing.JComponent

/**
 * [InlayCardHost] backed by `Editor.addComponentInlay`, verified present on the 2024.2 baseline.
 *
 * The returned `Inlay` is itself a [Disposable]; disposing it removes the inlay and disposes the
 * embedded Swing subtree, so callers just parent it to the right scope (the editor's disposable).
 * `relatesToPrecedingText(true)` keeps the card anchored to the line above it — the commented line —
 * as edits shift offsets. `FIT_VIEWPORT_WIDTH` makes the card span the editor width so it reads as
 * a review thread rather than a floating widget.
 */
internal class ComponentInlayCardHost(private val editor: Editor) : InlayCardHost {
    override fun addCardBelow(lineEndOffset: Int, card: JComponent): Disposable? {
        val properties = InlayProperties().relatesToPrecedingText(true)
        return editor.addComponentInlay(
            lineEndOffset,
            properties,
            card,
            ComponentInlayAlignment.FIT_VIEWPORT_WIDTH,
        )
    }
}
