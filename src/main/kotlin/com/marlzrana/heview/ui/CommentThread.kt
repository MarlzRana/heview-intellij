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
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.model.HeviewComment
import com.marlzrana.heview.model.HeviewReply
import com.marlzrana.heview.model.normalizedReplies
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel

/**
 * A comment thread rendered as an editor inlay card via [InlayCardHost].
 *
 * A thread is a stack of **replies**, each with its own author, status (Pending / Seen) and actions
 * (plan.html §5) — mirroring reviewa's per-comment UI:
 * - **edit** (pencil) and **delete** (trash) on every reply,
 * - **re-pend** (clock) only on a Seen reply,
 * - a persistent "Leave a comment" box that adds a new Pending reply to the thread.
 *
 * Two entry points, mutually exclusive per instance:
 * - [startCompose] opens an empty compose card for a *new* thread; `onSubmit` persists the first reply
 *   and returns the stored thread, then the card flips to the reply stack; Cancel discards. `onSubmit`
 *   lives on the method (not the constructor) so display cards can't be handed a compose callback.
 * - [startDisplay] renders an already-persisted thread straight as the reply stack.
 *
 * Reply / edit / delete / re-pend route to the caller-supplied callbacks (which mutate the store); the
 * resulting store change flows back as a [refreshDisplay], rebuilding the stack in place (and in every
 * split). [onDispose] is registered on the inlay so it runs on any teardown.
 *
 * EDT-only — all methods run on the event dispatch thread that owns the editor.
 */
internal class CommentThread(
    private val project: Project,
    private val host: InlayCardHost,
    private val lineEndOffset: Int,
    private val author: String,
    private val onReply: (comment: HeviewComment, replyText: String) -> Unit = { _, _ -> },
    private val onEditReply: (comment: HeviewComment, reply: HeviewReply, newText: String) -> Unit = { _, _, _ -> },
    private val onDeleteReply: (comment: HeviewComment, reply: HeviewReply) -> Unit = { _, _ -> },
    private val onRependReply: (comment: HeviewComment, reply: HeviewReply) -> Unit = { _, _ -> },
    private val onDispose: (thread: CommentThread) -> Unit = {},
) {
    private val panel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 10) }
    private var inlay: Disposable? = null
    private var disposed = false
    private var composeSubmit: ((text: String) -> HeviewComment)? = null
    // The thread this card currently shows; null while composing. Lets refreshDisplay skip a no-op
    // re-render and lets the compose→submit path own the first render itself.
    private var displayed: HeviewComment? = null
    // Which reply row is in inline-edit mode (null = none). While set, refreshDisplay is suppressed so
    // an in-progress edit isn't clobbered by an unrelated reconcile.
    private var editingIndex: Int? = null

    // Bumped every time the reply stack is (re)built, so a test can prove refreshDisplay's
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

    /** Place an already-persisted thread straight as the reply stack. Returns false if unplaceable. */
    fun startDisplay(comment: HeviewComment): Boolean {
        renderThread(comment)
        return place()
    }

    /**
     * Rebuild the reply stack if [comment] differs from what it currently shows — e.g. the consumption
     * watcher flipping the thread to Seen, or a reply/edit/re-pend mutating it. No-op while composing
     * (nothing displayed yet) or when unchanged. While an inline edit is open the rebuild is deferred so
     * the edit field survives, but [displayed] is still advanced to the latest version so Save/Cancel
     * renders that (a consume landing mid-edit isn't lost).
     */
    fun refreshDisplay(comment: HeviewComment) {
        if (disposed) return
        val shown = displayed ?: return
        if (shown == comment) return
        if (editingIndex != null) {
            displayed = comment
            return
        }
        renderThread(comment)
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

    // ---- compose (create the first reply of a new thread) ----

    private fun renderCompose(): EditorTextField {
        val input = commentInput(text = "", placeholder = "Leave a comment…")
        val submit = JButton("Submit").apply { addActionListener { submit(input.text) } }
        val cancel = JButton("Cancel").apply { addActionListener { dispose() } }
        setContent(center = input, south = buttonRow(cancel, submit))
        return input
    }

    /** Persist [text] via the compose callback and flip to the reply stack; blank/whitespace is dropped. */
    private fun submit(text: String) {
        // Trim only decides emptiness; the stored content is verbatim (matches reviewa).
        if (text.isBlank()) return
        val persist = checkNotNull(composeSubmit) { "submit() is only valid after startCompose" }
        renderThread(persist(text))
    }

    /** Drive the submit path without a real button click / EditorTextField (UI-lifecycle test). */
    @TestOnly
    internal fun submitForTest(text: String) = submit(text)

    // ---- display (the reply stack) ----

    private fun renderThread(comment: HeviewComment) {
        displayRenderCount++
        displayed = comment
        val replies = comment.normalizedReplies(author)
        val stack = JPanel(VerticalLayout(JBUI.scale(8)))
        replies.forEachIndexed { index, reply -> stack.add(replyRow(index, reply)) }
        stack.add(replyBox())
        setBody(stack)
    }

    /** One reply row: author + status chip and the edit/delete/(re-pend) icons, over its content. */
    private fun replyRow(index: Int, reply: HeviewReply): JPanel {
        if (index == editingIndex) return editRow(reply)
        val header = JPanel(BorderLayout()).apply {
            add(replyMetadata(reply), BorderLayout.WEST)
            add(rowActions(index, reply), BorderLayout.EAST)
        }
        val body = JBTextArea(reply.content).apply {
            isEditable = false
            // Non-focusable so clicks don't route Backspace/Enter/arrows to the host code editor.
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
        }
        return JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    /** The author + status chip shown at the left of a reply row (and its edit mode). */
    private fun replyMetadata(reply: HeviewReply): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JBLabel(reply.author))
            add(statusLabel(reply.status))
        }

    // Edit opens by row index (which row shows the field, within this render); delete/re-pend act on the
    // reply VALUE so a concurrent list change can't misroute them to the wrong reply.
    private fun rowActions(index: Int, reply: HeviewReply): JPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(iconButton(AllIcons.Actions.GC, "Delete") { deleteReply(reply) })
            add(iconButton(AllIcons.Actions.Edit, "Edit") { startEdit(index) })
            if (reply.status == CommentStatus.PROCESSED) {
                add(iconButton(AllIcons.Vcs.History, "Re-pend") { rependReply(reply) })
            }
        }

    /** The editing row swapped into inline edit mode (editable field + Save/Cancel). */
    private fun editRow(reply: HeviewReply): JPanel {
        val input = commentInput(text = reply.content, placeholder = "Edit comment…")
        val save = JButton("Save").apply { addActionListener { submitEdit(reply, input.text) } }
        val cancel = JButton("Cancel").apply { addActionListener { cancelEdit() } }
        focusLater(input)
        return JPanel(BorderLayout()).apply {
            add(replyMetadata(reply), BorderLayout.NORTH)
            add(input, BorderLayout.CENTER)
            add(buttonRow(cancel, save), BorderLayout.SOUTH)
        }
    }

    /** The thread-level "Leave a comment" reply input; a reply adds a new Pending reply. */
    private fun replyBox(): JPanel {
        val input = commentInput(text = "", placeholder = "Leave a comment…", heightPx = 40)
        val reply = JButton("Reply").apply { addActionListener { submitReply(input.text) } }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(6)
            add(input, BorderLayout.CENTER)
            add(buttonRow(reply), BorderLayout.SOUTH)
        }
    }

    // ---- reply actions (each reads the shown thread; the store change re-renders the stack) ----

    private fun submitReply(text: String) {
        if (text.isBlank()) return
        val current = displayed ?: return
        onReply(current, text)
    }

    private fun startEdit(index: Int) {
        val current = displayed ?: return
        editingIndex = index
        renderThread(current)
    }

    private fun submitEdit(reply: HeviewReply, text: String) {
        if (text.isBlank()) return
        val current = displayed ?: return
        // Clear the edit flag BEFORE firing: its synchronous reconcile calls refreshDisplay, which must
        // be allowed through to rebuild the stack back into display mode with the new content.
        editingIndex = null
        onEditReply(current, reply, text)
    }

    private fun cancelEdit() {
        val current = displayed ?: return
        editingIndex = null
        renderThread(current)
    }

    private fun deleteReply(reply: HeviewReply) {
        val current = displayed ?: return
        onDeleteReply(current, reply)
    }

    private fun rependReply(reply: HeviewReply) {
        val current = displayed ?: return
        onRependReply(current, reply)
    }

    // ---- test seams: drive the reply actions without real clicks / EditorTextFields ----

    @TestOnly
    internal fun replyForTest(text: String) = submitReply(text)

    @TestOnly
    internal fun editReplyForTest(index: Int, text: String) {
        startEdit(index)
        submitEdit(replyAt(index) ?: return, text)
    }

    @TestOnly
    internal fun deleteReplyForTest(index: Int) = replyAt(index)?.let { deleteReply(it) }

    @TestOnly
    internal fun rependReplyForTest(index: Int) = replyAt(index)?.let { rependReply(it) }

    /** Open the inline edit on a row without submitting — for the refresh-suppression test. */
    @TestOnly
    internal fun startEditForTest(index: Int) = startEdit(index)

    /** Whether an inline edit is currently open — lets a test assert the card returned to display mode. */
    @TestOnly
    internal fun isEditingForTest(): Boolean = editingIndex != null

    private fun replyAt(index: Int): HeviewReply? = displayed?.normalizedReplies(author)?.getOrNull(index)

    /** The status of each shown reply, in order — lets a test assert per-reply Pending/Seen state. */
    @TestOnly
    internal fun replyStatusesForTest(): List<CommentStatus> =
        displayed?.normalizedReplies(author)?.map { it.status } ?: emptyList()

    // ---- shared bits ----

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
            isRolloverEnabled = true
            // A plain JButton carries the platform's ~72px minimum button width, which spreads icon-only
            // buttons far apart; pin a tight, icon-sized bound so they pack together (bypasses the UI's
            // getPreferredSize once explicitly set).
            border = JBUI.Borders.empty()
            val dim = Dimension(icon.iconWidth + JBUI.scale(6), icon.iconHeight + JBUI.scale(6))
            preferredSize = dim
            minimumSize = dim
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

    /** The status chip in a reply header — orange "Pending" (actionable) vs green "Seen" (consumed). */
    private fun statusLabel(status: CommentStatus): JBLabel = when (status) {
        CommentStatus.PENDING -> JBLabel("Pending").apply { foreground = JBColor.ORANGE }
        CommentStatus.PROCESSED -> JBLabel("Seen").apply { foreground = JBColor.GREEN }
    }

    private fun buttonRow(vararg buttons: JButton): JPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { buttons.forEach { add(it) } }

    /** Compose layout: a single input over a button row. */
    private fun setContent(center: Component, south: Component) {
        panel.removeAll()
        panel.add(center, BorderLayout.CENTER)
        panel.add(south, BorderLayout.SOUTH)
        panel.revalidate()
        panel.repaint()
    }

    /** Display layout: the whole reply stack as the card body. */
    private fun setBody(component: Component) {
        panel.removeAll()
        panel.add(component, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }
}
