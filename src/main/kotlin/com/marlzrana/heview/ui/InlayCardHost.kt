package com.marlzrana.heview.ui

import com.intellij.openapi.Disposable
import javax.swing.JComponent

/**
 * The single seam through which heview embeds a Swing component in an editor.
 *
 * The underlying platform API for interactive in-editor cards (`Editor.addComponentInlay`, backed
 * by `EditorEmbeddedComponentManager`) is `@ApiStatus.Experimental` and lives in `platform-impl`
 * (see plan.html §10). Isolating it behind this interface means the experimental call sits in
 * exactly one implementation and can be swapped — `addComponentInlay` ↔ `EditorEmbeddedComponentManager`
 * ↔ a stable gutter + `JBPopup` fallback — without touching any call site.
 */
internal interface InlayCardHost {
    /**
     * Add [card] as a full-width block inlay directly below the line whose end is [lineEndOffset].
     *
     * @return a [Disposable] that removes the inlay and tears the component down when disposed, or
     *   `null` if the host could not place it (e.g. the editor does not support component inlays).
     */
    fun addCardBelow(lineEndOffset: Int, card: JComponent): Disposable?
}
