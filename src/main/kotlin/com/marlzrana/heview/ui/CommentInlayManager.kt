package com.marlzrana.heview.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.marlzrana.heview.model.HeviewComment
import com.marlzrana.heview.model.newFileComment
import com.marlzrana.heview.storage.CommentStore
import com.marlzrana.heview.util.HeviewTime
import org.jetbrains.annotations.TestOnly

/**
 * Owns the inlay cards for a project: it is the single controller that creates, tracks and disposes
 * every [CommentThread], mirroring reviewa's comment controller.
 *
 * Three triggers keep the on-screen cards in sync with the shared [CommentStore]:
 * - **Editor lifecycle** — an [EditorFactoryListener] renders display cards when an editor for a
 *   commented file opens (including splits and reopened projects) and tears them down on release.
 * - **Store changes** — a change listener reconciles every open editor so a comment added or deleted
 *   in one editor appears/disappears in every split showing the same file.
 * - **External file reloads** — a [FileDocumentManagerListener] reconciles when an open file is
 *   reloaded from disk (an agent editing a file to resolve a comment), which can dispose the inlays but
 *   fires no store change. See [onFileContentReloaded] (it trusts the reload-shifted anchors, rebuilding
 *   only cards the reload actually disposed). The same listener's `beforeDocumentSaving` writes each moved
 *   anchor's line back to the pool so the durable `line_number` stays in step with the on-disk file the
 *   agent reads. See [onBeforeDocumentSaving].
 *
 * The create flow is routed through [compose] (rather than living in the action) precisely so this
 * manager tracks the resulting thread before [CommentStore.save] fires: reconcile then sees the card
 * as already present and never renders a duplicate in the composing editor.
 *
 * Cards are placed at a live document position, not the stored `line_number`: one [RangeMarker] per
 * comment ([anchors]) — created on first display, or promoted from the compose anchor on submit —
 * lets a newly opened split place the card on the comment's *current* line even after edits above it.
 *
 * EDT-confined: [init] is invoked on the EDT and every callback (editor events, store change, the
 * hydrate hop) reaches us on the EDT, so the maps need no locking.
 */
@Service(Service.Level.PROJECT)
internal class CommentInlayManager(private val project: Project) : Disposable {
    // A test can point the manager at a temp-dir store instead of the shared application service.
    @set:TestOnly
    internal var storeOverride: CommentStore? = null
    private val store: CommentStore get() = storeOverride ?: service()
    private val author: String get() = System.getProperty("user.name") ?: "You"

    // Every relevant open editor -> its display cards keyed by comment uuid. An editor stays in the
    // map (even with an empty value) while open, so a later store change can render new comments
    // into an editor that currently shows none.
    private val rendered = HashMap<Editor, MutableMap<String, CommentThread>>()

    // In-flight compose cards, per editor, kept out of `rendered` so reconcile never cancels them but
    // forget()/dispose() still tear them down (a compose card open at project close / plugin unload).
    private val composing = HashMap<Editor, MutableSet<CommentThread>>()

    // One document RangeMarker per comment uuid; tracks the live line so recreated cards don't use the
    // stale persisted line_number. Disposed when the comment leaves the store or the manager disposes.
    private val anchors = HashMap<String, RangeMarker>()

    private var initialized = false

    // Which InlayCardHost backs a given editor. Real code uses the experimental component-inlay host;
    // the UI-lifecycle test swaps in a fake so it can assert reconcile/tracking without a live inlay.
    @set:TestOnly
    internal var hostFactory: (Editor) -> InlayCardHost = { ComponentInlayCardHost(it) }

    /** Wire the listeners, render already-open editors, and hydrate the store. Idempotent; EDT-only. */
    fun init() {
        if (initialized) return
        initialized = true

        val subscription = store.addChangeListener { onStoreChanged() }
        Disposer.register(this, subscription)

        // FileDocumentManager is application-level; the connection is parented to this manager, so the
        // subscription is removed on dispose. We receive reloads across every project — the handler's
        // document-identity match makes an unrelated project's reload a cheap no-op.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun fileContentReloaded(file: VirtualFile, document: Document) =
                    onFileContentReloaded(document)

                override fun beforeDocumentSaving(document: Document) =
                    onBeforeDocumentSaving(document)
            },
        )

        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    if (isRelevant(event.editor)) reconcile(event.editor)
                }

                override fun editorReleased(event: EditorFactoryEvent) = forget(event.editor)
            },
            this,
        )

        // Editors restored before this ran (a reopened project) never fire editorCreated for us.
        EditorFactory.getInstance().allEditors
            .filter { isRelevant(it) }
            .forEach { reconcile(it) }

        // Fills the index from disk on a background thread, then reconciles via the change listener.
        store.hydrate()
    }

    /** Open a compose card on the caret line of [editor]; persist and track it on submit. */
    fun compose(editor: Editor): CommentThread? {
        // The action can fire before the startup activity ran init(); this is idempotent and EDT-only.
        init()

        val document = editor.document
        val absPath = localAbsPath(editor) ?: return null
        val workspace = project.basePath ?: return null
        // The caret can sit in virtual space (past the last line); clamp so getLineEndOffset is safe.
        val line = clampLine(document, editor.caretModel.logicalPosition.line)
        val lineEndOffset = document.getLineEndOffset(line)
        // Track the anchor line across edits made while composing; read it again at submit.
        val anchor = document.createRangeMarker(document.getLineStartOffset(line), lineEndOffset)
        var promoted = false

        val thread = newThread(
            editor = editor,
            lineEndOffset = lineEndOffset,
            onDispose = { disposed ->
                // If the anchor was promoted into the registry it belongs to the comment now; the
                // registry disposes it (store-removal / manager dispose), not this card's teardown.
                if (!promoted && anchor.isValid) anchor.dispose()
                composing[editor]?.remove(disposed)
                rendered[editor]?.values?.remove(disposed)
            },
        )

        val placed = thread.startCompose { text ->
            val rawLine = if (anchor.isValid) document.getLineNumber(anchor.startOffset) else line
            val anchorLine = clampLine(document, rawLine)
            val lineContent = document.getText(
                TextRange(document.getLineStartOffset(anchorLine), document.getLineEndOffset(anchorLine)),
            )
            val comment = newFileComment(
                workspace = workspace,
                absPath = absPath,
                line0Based = anchorLine,
                lineContent = lineContent,
                content = text,
                author = author,
                createdAt = HeviewTime.nowIso(),
            )
            // Promote the compose anchor (it has tracked edits since caret time) so recreated cards in
            // other splits place on the current line, not the persisted one.
            promoted = true
            anchors[comment.uuid] = anchor
            // Track BEFORE save: save() fires the change listener synchronously, which reconciles this
            // editor — registering first means reconcile treats the card as present and won't render a
            // duplicate. Other editors on this file still get their own card.
            track(editor, comment.uuid, thread)
            composing[editor]?.remove(thread)
            store.save(comment)
            comment
        }

        if (!placed) {
            // startCompose only runs onSubmit after place() succeeds, so the anchor was never promoted.
            if (anchor.isValid) anchor.dispose()
            thisLogger().warn("heview: inline comments are not available in this editor")
            return null
        }
        composing.getOrPut(editor) { HashSet() }.add(thread)
        return thread
    }

    private fun onStoreChanged() {
        // The map holds every open relevant editor, so this covers them all.
        rendered.keys.toList().forEach { reconcile(it) }
    }

    /**
     * A file open in the IDE was reloaded from disk — the core "an agent edited the file to resolve a
     * comment" flow (plan.html §5/§6). The whole-document reload disposes the component inlays and can
     * leave a per-uuid [RangeMarker] valid but on a shifted line, yet fires no [CommentStore] change, so
     * nothing else reconciles: the cards would go blank/stale until the file was reopened.
     *
     * Drop this document's anchors so a rebuilt card places from the persisted `line_number` — the last
     * known-good semantic line, the same one a freshly opened editor uses — rather than the marker the
     * wholesale replace left behind (in-IDE edits still track live; only an external reload falls back).
     * Then reconcile every open editor of the file to recreate the cards the reload disposed. All open
     * editors of one file share this [Document] instance across a reload, so identity matches every
     * split. EDT-confined: a reload runs on the EDT, like every other callback here.
     */
    private fun onFileContentReloaded(document: Document) {
        // A reload fires no store change, so nothing else reconciles: cards the reload disposed (its text
        // range was replaced) would stay gone until reopen. Reconcile every open editor of this document to
        // recreate them. We deliberately do NOT drop the anchors: IntelliJ's reload diffs the text (common
        // prefix/suffix trimming in DocumentImpl.replaceString), so a surviving RangeMarker is correctly
        // shifted and stays attached to its code — trusting it beats falling back to a line_number the
        // external edit never updated. Only a marker the reload genuinely invalidated is rebuilt from the
        // persisted line_number, which currentLineEndOffset already handles when a disposed card is recreated.
        rendered.keys
            .filter { it.document === document }
            .forEach { reconcile(it) }
    }

    /**
     * [document] is being flushed to disk: write each of its comments' *current* line back to the pool
     * (cycle3 #2). The persisted `line_number`/`line_content` were frozen at submit, so after in-IDE edits
     * above a comment the on-disk number goes stale — and the injector, a reopen, and the reload fallback
     * all read it. Saving is the right sync point: the agent reads the file from disk, so the pool should
     * match it precisely when the document lands on disk (an unsaved edit correctly leaves the pool alone —
     * the agent still sees the old file). The live [RangeMarker] gives the current line; a marker that the
     * edit invalidated is skipped (no defensible line to record). The store no-ops when nothing changed.
     */
    private fun onBeforeDocumentSaving(document: Document) {
        anchors.entries
            .filter { it.value.document === document && it.value.isValid }
            .forEach { (uuid, marker) ->
                val line = clampLine(document, document.getLineNumber(marker.startOffset))
                val lineContent = document.getText(
                    TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)),
                )
                store.updateLocation(uuid, line + 1, lineContent)
            }
    }

    /** Bring [editor]'s display cards in line with the store: add missing, dispose deleted. */
    private fun reconcile(editor: Editor) {
        if (editor.isDisposed) {
            forget(editor)
            return
        }
        val path = localAbsPath(editor)
        val cards = rendered.getOrPut(editor) { LinkedHashMap() }
        val desired = if (path == null) emptyMap() else store.forAbsPath(path).associateBy { it.uuid }

        // Remove cards whose comment is gone. Drop from the map before disposing so the card's
        // onDispose never observes a stale entry; retire the anchor once no editor shows the comment.
        cards.keys.filter { it !in desired }.forEach { uuid ->
            cards.remove(uuid)?.dispose()
            retireAnchorIfUnused(uuid)
        }

        // Add cards for comments not yet shown; refresh a shown card whose comment changed in place
        // (e.g. the consumption watcher flipping a comment to PROCESSED → the card relabels to "Seen").
        for ((uuid, comment) in desired) {
            val existing = cards[uuid]
            if (existing != null) {
                existing.refreshDisplay(comment)
                continue
            }
            displayThread(editor, comment)?.let { cards[uuid] = it }
        }
    }

    private fun displayThread(editor: Editor, comment: HeviewComment): CommentThread? {
        val thread = newThread(
            editor = editor,
            lineEndOffset = currentLineEndOffset(editor.document, comment),
            onDispose = { disposed ->
                // If the platform disposes the inlay (e.g. its text range was deleted), drop the stale
                // entry so a later reconcile recreates the card instead of skipping it forever.
                rendered[editor]?.values?.remove(disposed)
                retireAnchorIfUnused(comment.uuid)
            },
        )
        if (thread.startDisplay(comment)) return thread
        retireAnchorIfUnused(comment.uuid) // placement declined — don't leak the anchor just created
        return null
    }

    /**
     * A [CommentThread] wired to the store: the per-reply state-machine actions (plan.html §5). A reply
     * adds a new PENDING reply; edit/re-pend revive that reply; delete removes it (the last reply drops
     * the thread). Timestamps come from [HeviewTime.nowIso] and new replies are authored by [author].
     * Shared by the compose and display paths; only [lineEndOffset] and [onDispose] differ.
     */
    private fun newThread(
        editor: Editor,
        lineEndOffset: Int,
        onDispose: (CommentThread) -> Unit,
    ): CommentThread = CommentThread(
        project = project,
        host = hostFactory(editor),
        lineEndOffset = lineEndOffset,
        author = author,
        onReply = { comment, text -> store.addReply(comment.uuid, text, author, HeviewTime.nowIso()) },
        onEditReply = { comment, reply, text -> store.editReply(comment.uuid, reply, text, HeviewTime.nowIso()) },
        onDeleteReply = { comment, reply -> store.deleteReply(comment.uuid, reply) },
        onRependReply = { comment, reply -> store.rependReply(comment.uuid, reply, HeviewTime.nowIso()) },
        onDispose = onDispose,
    )

    /**
     * The line-end offset where [comment]'s card should sit *now*. All editors of a file share one
     * Document, so a single per-uuid [RangeMarker] (created here on first display, at the persisted
     * line, and reused thereafter) follows edits — a split opened after lines were inserted above the
     * comment places the card on the current line, not the stale `line_number`.
     */
    private fun currentLineEndOffset(document: Document, comment: HeviewComment): Int {
        val existing = anchors[comment.uuid]
        val marker = if (existing != null && existing.isValid && existing.document === document) {
            existing
        } else {
            existing?.dispose() // invalid, or from a previous Document instance for this file
            val line = clampLine(document, comment.lineNumber - 1)
            document.createRangeMarker(document.getLineStartOffset(line), document.getLineEndOffset(line))
                .also { anchors[comment.uuid] = it }
        }
        return document.getLineEndOffset(clampLine(document, document.getLineNumber(marker.startOffset)))
    }

    /** Clamp a 0-based line into the document, tolerating an empty document. */
    private fun clampLine(document: Document, line: Int): Int =
        line.coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))

    /** Dispose a comment's shared anchor once no open editor still displays it. */
    private fun retireAnchorIfUnused(uuid: String) {
        if (rendered.values.none { it.containsKey(uuid) }) anchors.remove(uuid)?.dispose()
    }

    private fun track(editor: Editor, uuid: String, thread: CommentThread) {
        rendered.getOrPut(editor) { LinkedHashMap() }[uuid] = thread
    }

    /** Drop tracking for [editor] and dispose its cards (display + any in-flight compose). No-op if untracked. */
    private fun forget(editor: Editor) {
        // Close path: the editor is going away, so skip scroll preservation on its cards.
        composing.remove(editor)?.toList()?.forEach { it.dispose(preserveScroll = false) }
        val cards = rendered.remove(editor) ?: return
        val uuids = cards.keys.toList()
        cards.values.toList().forEach { it.dispose(preserveScroll = false) }
        uuids.forEach { retireAnchorIfUnused(it) } // drop anchors for comments no other editor shows
    }

    private fun isRelevant(editor: Editor): Boolean =
        editor.project == project &&
            editor.editorKind == EditorKind.MAIN_EDITOR &&
            localAbsPath(editor) != null

    /** This editor's file as an absolute OS path, or null if it isn't a local file. */
    private fun localAbsPath(editor: Editor): String? =
        FileDocumentManager.getInstance().getFile(editor.document)
            ?.takeIf { it.isInLocalFileSystem }
            ?.toNioPath()?.toString()

    override fun dispose() {
        (rendered.keys + composing.keys).toSet().forEach { forget(it) }
        anchors.values.forEach { it.dispose() }
        anchors.clear()
    }

    /** Live anchor count — lets a test assert no RangeMarker leaks on the unplaceable path. */
    @TestOnly
    internal fun anchorCountForTest(): Int = anchors.size

    /** The display card shown for [uuid] in [editor], if any — lets a test observe its render count. */
    @TestOnly
    internal fun cardForTest(editor: Editor, uuid: String): CommentThread? = rendered[editor]?.get(uuid)

    /** Drive the file-reload handler directly — a real VFS reload is flaky to script headlessly. */
    @TestOnly
    internal fun simulateFileContentReloadedForTest(document: Document) = onFileContentReloaded(document)

    /** Drive the save-time line-number writeback directly — headless saves don't fire the real listener. */
    @TestOnly
    internal fun simulateBeforeDocumentSavingForTest(document: Document) = onBeforeDocumentSaving(document)
}
