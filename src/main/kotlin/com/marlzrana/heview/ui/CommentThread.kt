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
import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.model.HeviewComment
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * A single comment thread rendered as an editor inlay card via [InlayCardHost].
 *
 * Two entry points, mutually exclusive per instance:
 * - [startCompose] opens an empty compose card; its `onSubmit` argument persists and returns the
 *   stored record, then the card flips to display; Delete calls [onDelete] and removes the inlay;
 *   Cancel discards. Used for the create flow. `onSubmit` lives on the method (not the constructor)
 *   so display cards can't be handed a compose callback they must never use.
 * - [startDisplay] renders an already-persisted comment straight in display mode (no compose step).
 *   Used by the [com.marlzrana.heview.ui.CommentInlayManager] when hydrating existing comments into
 *   newly opened editors.
 *
 * [onDispose] is registered on the inlay, so it runs on *any* teardown — Cancel/Delete or the
 * platform disposing the inlay when the editor closes — letting the caller release resources it
 * owns (e.g. the line anchor) and drop its bookkeeping. It receives this thread so the owner can
 * identify which card was torn down.
 *
 * A display card shows its comment's status (Pending / Seen); [refreshDisplay] re-renders it in place
 * when the underlying comment changes — how the consumption watcher's PENDING→PROCESSED "Seen" flip
 * reaches an already-open card. Replies / edit / re-pend arrive in later increments.
 *
 * EDT-only — all methods run on the event dispatch thread that owns the editor.
 */
internal class CommentThread(
    private val project: Project,
    private val host: InlayCardHost,
    private val lineEndOffset: Int,
    private val author: String,
    private val onDelete: (comment: HeviewComment) -> Unit,
    private val onDispose: (thread: CommentThread) -> Unit = {},
) {
    private val panel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 10) }
    private var inlay: Disposable? = null
    private var disposed = false
    private var composeSubmit: ((text: String) -> HeviewComment)? = null
    // The comment this card currently shows in display mode; null while composing. Lets refreshDisplay
    // skip a no-op re-render and lets the compose→submit path own the first display render itself.
    private var displayed: HeviewComment? = null

    /** Place the compose card below the target line. Returns false if the host could not place it. */
    fun startCompose(onSubmit: (text: String) -> HeviewComment): Boolean {
        composeSubmit = onSubmit
        val input = renderCompose()
        if (!place()) return false
        // The EditorTextField creates its editor only once shown, so focus after the inlay is placed
        // and realized (next EDT tick), guarded so we never focus a torn-down card or closed project.
        ApplicationManager.getApplication().invokeLater(
            { if (!disposed) IdeFocusManager.getInstance(project).requestFocus(input, true) },
            project.disposed,
        )
        return true
    }

    /** Place an already-persisted comment straight in display mode. Returns false if unplaceable. */
    fun startDisplay(comment: HeviewComment): Boolean {
        renderDisplay(comment)
        return place()
    }

    /**
     * Re-render this card if [comment] differs from what it currently shows — e.g. the consumption
     * watcher flipping PENDING→PROCESSED ("Seen"). No-op while still composing (nothing displayed yet,
     * so the submit path owns the first render) or when the comment is unchanged, so steady-state
     * reconciles and the compose→submit sequence never needlessly rebuild the card.
     */
    fun refreshDisplay(comment: HeviewComment) {
        if (disposed) return
        val shown = displayed ?: return
        if (shown == comment) return
        renderDisplay(comment)
    }

    /** Insert the card below the target line and wire teardown. Returns false if the host declines. */
    private fun place(): Boolean {
        val placed = host.addCardBelow(lineEndOffset, panel) ?: return false
        inlay = placed
        // Run onDispose on any teardown, including the platform disposing the inlay on editor close;
        // set `disposed` so queued UI callbacks (focus, delete-enable) become no-ops.
        Disposer.register(placed, Disposable { disposed = true; onDispose(this@CommentThread) })
        return true
    }

    /**
     * Tear down the inlay; safe to call more than once. Triggers the registered [onDispose].
     *
     * [preserveScroll] keeps the editor's scroll position as the inlay is removed — right for
     * app-initiated removals (delete/reconcile/cancel) where the editor stays open. Pass `false` on
     * the editor-close path: the editor is going away, so the scroll-model work would be wasted.
     */
    fun dispose(preserveScroll: Boolean = true) {
        if (disposed) return
        disposed = true
        inlay?.let { if (preserveScroll) host.disposeCard(it) else Disposer.dispose(it) } // fires onDispose
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
        val submit = JButton("Submit").apply { addActionListener { submit(input.text) } }
        val cancel = JButton("Cancel").apply { addActionListener { dispose() } }
        setContent(center = input, south = buttonRow(cancel, submit))
        return input
    }

    /** Persist [text] via the compose callback and flip to display; blank/whitespace is dropped. */
    private fun submit(text: String) {
        // Trim only decides emptiness; the stored content is verbatim (matches reviewa).
        if (text.isBlank()) return
        val persist = checkNotNull(composeSubmit) { "submit() is only valid after startCompose" }
        renderDisplay(persist(text))
    }

    /** Drive the submit path without a real button click / EditorTextField (UI-lifecycle test). */
    @TestOnly
    internal fun submitForTest(text: String) = submit(text)

    private fun renderDisplay(current: HeviewComment) {
        displayed = current
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JBLabel(author))
            add(statusLabel(current.status))
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

    /** The status chip in the card header — orange "Pending" (actionable) vs green "Seen" (consumed). */
    private fun statusLabel(status: CommentStatus): JBLabel = when (status) {
        CommentStatus.PENDING -> JBLabel("Pending").apply { foreground = JBColor.ORANGE }
        CommentStatus.PROCESSED -> JBLabel("Seen").apply { foreground = JBColor.GREEN }
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
