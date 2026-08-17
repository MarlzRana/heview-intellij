package com.marlzrana.heview.ui

import com.intellij.icons.AllIcons
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
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel

/**
 * A single comment thread rendered as an editor inlay card via [InlayCardHost].
 *
 * Two entry points, mutually exclusive per instance:
 * - [startCompose] opens an empty compose card; its `onSubmit` argument persists and returns the
 *   stored record, then the card flips to display; Cancel discards. Used for the create flow.
 *   `onSubmit` lives on the method (not the constructor) so display cards can't be handed a compose
 *   callback they must never use.
 * - [startDisplay] renders an already-persisted comment straight in display mode (no compose step).
 *   Used by the [com.marlzrana.heview.ui.CommentInlayManager] when hydrating existing comments into
 *   newly opened editors.
 *
 * A display card shows the comment's author + status chip (Pending / Seen), a row of icon actions
 * (delete, edit, and — only on a Seen comment — re-pend), and a persistent "Leave a comment" reply
 * box (plan.html §5 state machine; parity with reviewa's thread UI). Reply / edit / re-pend all route
 * to the caller-supplied callbacks, which revive the comment to PENDING and re-persist it; the store
 * change then flows back as a [refreshDisplay] so the card (and every other split) relabels in place.
 *
 * [onDispose] is registered on the inlay, so it runs on *any* teardown — Cancel/Delete or the
 * platform disposing the inlay when the editor closes — letting the caller release resources it owns
 * (e.g. the line anchor) and drop its bookkeeping. It receives this thread so the owner can identify
 * which card was torn down.
 *
 * EDT-only — all methods run on the event dispatch thread that owns the editor.
 */
internal class CommentThread(
    private val project: Project,
    private val host: InlayCardHost,
    private val lineEndOffset: Int,
    private val author: String,
    private val onDelete: (comment: HeviewComment) -> Unit,
    private val onReply: (comment: HeviewComment, replyText: String) -> Unit = { _, _ -> },
    private val onEdit: (comment: HeviewComment, newContent: String) -> Unit = { _, _ -> },
    private val onRepend: (comment: HeviewComment) -> Unit = {},
    private val onDispose: (thread: CommentThread) -> Unit = {},
) {
    private val panel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 10) }
    private var inlay: Disposable? = null
    private var disposed = false
    private var composeSubmit: ((text: String) -> HeviewComment)? = null
    // The comment this card currently shows in display mode; null while composing. Lets refreshDisplay
    // skip a no-op re-render and lets the compose→submit path own the first display render itself.
    private var displayed: HeviewComment? = null
    // True while the inline edit field is open. Its in-progress text must survive an unrelated store
    // reconcile, so refreshDisplay is suppressed until the edit is saved or cancelled.
    private var editing = false

    // Bumped every time the display card is (re)built, so a test can prove refreshDisplay's
    // no-op-when-unchanged guard actually skips a rebuild on unrelated store changes.
    @get:TestOnly
    internal var displayRenderCount = 0
        private set

    /** Place the compose card below the target line. Returns false if the host could not place it. */
    fun startCompose(onSubmit: (text: String) -> HeviewComment): Boolean {
        composeSubmit = onSubmit
        val input = renderCompose()
        if (!place()) return false
        focusLater(input)
        return true
    }

    /** Place an already-persisted comment straight in display mode. Returns false if unplaceable. */
    fun startDisplay(comment: HeviewComment): Boolean {
        renderDisplay(comment)
        return place()
    }

    /**
     * Re-render this card if [comment] differs from what it currently shows — e.g. the consumption
     * watcher flipping PENDING→PROCESSED ("Seen"), or a reply/edit/re-pend reviving it to PENDING.
     * No-op while still composing (nothing displayed yet, so the submit path owns the first render),
     * while an inline edit is open (its text must not be clobbered), or when the comment is unchanged,
     * so steady-state reconciles and the compose→submit sequence never needlessly rebuild the card.
     */
    fun refreshDisplay(comment: HeviewComment) {
        if (disposed || editing) return
        val shown = displayed ?: return
        if (shown == comment) return
        renderDisplay(comment)
    }

    /** Insert the card below the target line and wire teardown. Returns false if the host declines. */
    private fun place(): Boolean {
        val placed = host.addCardBelow(lineEndOffset, panel) ?: return false
        inlay = placed
        // Run onDispose on any teardown, including the platform disposing the inlay on editor close;
        // set `disposed` so queued UI callbacks (focus) become no-ops.
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
        val input = commentInput(text = "", placeholder = "Leave a comment…")
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

    private fun renderDisplay(current: HeviewComment) {
        displayRenderCount++
        displayed = current
        editing = false
        val header = JPanel(BorderLayout()).apply {
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    add(JBLabel(author))
                    add(statusLabel(current.status))
                },
                BorderLayout.WEST,
            )
            add(actionRow(current), BorderLayout.EAST)
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
        setContent(center = body, south = replyBox(), north = header)
    }

    /** The delete / edit / re-pend icon actions; re-pend appears only on a Seen (PROCESSED) comment. */
    private fun actionRow(current: HeviewComment): JPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(iconButton(AllIcons.Actions.GC, "Delete") { onDelete(current); dispose() })
            add(iconButton(AllIcons.Actions.Edit, "Edit") { startEdit() })
            if (current.status == CommentStatus.PROCESSED) {
                add(iconButton(AllIcons.Actions.Rollback, "Re-pend") { repend() })
            }
        }

    /** The persistent "Leave a comment" reply input; a reply revives the thread to Pending. */
    private fun replyBox(): JPanel {
        val input = commentInput(text = "", placeholder = "Leave a comment…", heightPx = 40)
        val reply = JButton("Reply").apply { addActionListener { submitReply(input.text) } }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(6)
            add(input, BorderLayout.CENTER)
            add(buttonRow(reply), BorderLayout.SOUTH)
        }
    }

    /** Swap the body for an editable field; Save revives + re-persists, Cancel restores the display. */
    private fun startEdit() {
        val current = displayed ?: return
        editing = true
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JBLabel(author))
            add(statusLabel(current.status))
        }
        val input = commentInput(text = current.content, placeholder = "Edit comment…")
        val save = JButton("Save").apply { addActionListener { submitEdit(input.text) } }
        val cancel = JButton("Cancel").apply { addActionListener { cancelEdit() } }
        setContent(center = input, south = buttonRow(cancel, save), north = header)
        focusLater(input)
    }

    private fun submitReply(text: String) {
        if (text.isBlank()) return
        val current = displayed ?: return
        onReply(current, text) // store change → reconcile → refreshDisplay rebuilds (reply box reset)
    }

    private fun submitEdit(text: String) {
        if (text.isBlank()) return
        val current = displayed ?: return
        // Clear `editing` BEFORE firing onEdit: its synchronous reconcile calls refreshDisplay, which
        // must be allowed through to rebuild the card back into display mode with the new content.
        editing = false
        onEdit(current, text)
    }

    private fun cancelEdit() {
        val current = displayed ?: return
        editing = false
        renderDisplay(current)
    }

    private fun repend() {
        val current = displayed ?: return
        onRepend(current) // store change → reconcile → refreshDisplay relabels Seen → Pending
    }

    /** Drive the submit path without a real button click / EditorTextField (UI-lifecycle test). */
    @TestOnly
    internal fun submitForTest(text: String) = submit(text)

    /** Drive reply / edit / re-pend without real clicks — for the UI-lifecycle test. */
    @TestOnly
    internal fun replyForTest(text: String) = submitReply(text)

    @TestOnly
    internal fun editForTest(newText: String) {
        startEdit()
        submitEdit(newText)
    }

    @TestOnly
    internal fun rependForTest() = repend()

    /** Whether this card currently offers the re-pend action (i.e. it is showing a Seen comment). */
    @TestOnly
    internal fun offersRependForTest(): Boolean = displayed?.status == CommentStatus.PROCESSED

    /** A real embedded editor (owns Backspace/Enter/arrows/undo so they don't leak to the code editor). */
    private fun commentInput(text: String, placeholder: String, heightPx: Int = 64): EditorTextField =
        EditorTextField(text, project, PlainTextFileType.INSTANCE).apply {
            setOneLineMode(false)
            setPlaceholder(placeholder)
            // Fixed initial height; BorderLayout.CENTER stretches the width to the inlay.
            preferredSize = Dimension(1, JBUI.scale(heightPx))
            addSettingsProvider { it.settings.isUseSoftWraps = true }
        }

    /** A borderless header action button — just its icon and a tooltip. */
    private fun iconButton(icon: Icon, tooltip: String, onClick: () -> Unit): JButton =
        JButton(icon).apply {
            toolTipText = tooltip
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
            margin = JBUI.emptyInsets()
            addActionListener { onClick() }
        }

    /** Request focus on [component] after the inlay is placed and realized (next EDT tick). */
    private fun focusLater(component: Component) {
        // The EditorTextField creates its editor only once shown, so focus after placement, guarded so
        // we never focus a torn-down card or closed project.
        ApplicationManager.getApplication().invokeLater(
            { if (!disposed) IdeFocusManager.getInstance(project).requestFocus(component, true) },
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
