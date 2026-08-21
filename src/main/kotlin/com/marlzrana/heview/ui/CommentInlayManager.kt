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
import com.marlzrana.heview.model.CommentStatus
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
 *   only cards the reload actually disposed, and binning a comment the reload *orphaned* — its commented
 *   line's text is gone from the reloaded file). The same listener's `beforeDocumentSaving` writes
 *   each moved anchor's line back to the pool so the durable `line_number` stays in step with the on-disk
 *   file the agent reads. See [onBeforeDocumentSaving].
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
     * comment" flow (plan.html §5/§6). The reload can dispose the component inlays yet fires no
     * [CommentStore] change, so nothing else reconciles: the cards would go blank until the file was
     * reopened. Recreate them, and keep the durable pool in step with the just-changed on-disk file.
     *
     * The order matters — each step mutates [anchors] / fires reconciles that would trip over the others:
     * 1. **Classify** the document's anchors against the reloaded text: **orphans** (see [isOrphaned] — their
     *    commented line's text is gone from the file) and **invalidated** markers that are NOT orphans.
     * 2. **Write back survivors' moved lines** ([writeBackAnchors], skipping the orphans). The reload IS the
     *    on-disk change the save-time writeback exists for, but an external edit doesn't dirty the document, so
     *    `beforeDocumentSaving` never runs for it. This runs FIRST so that [CommentStore.binFromPool]'s
     *    synchronous reconcile (below) can't mint a `line_number`-fallback marker that this writeback would
     *    then persist as the disk position (it wouldn't be one).
     * 3. **Re-anchor invalidated survivors.** A real reload diffs the text (common-affix trim in
     *    `DocumentImpl.replaceString`) and usually *shifts* a marker, but a both-ends edit (an import at the top
     *    *and* a function at the bottom) replaces the whole middle and invalidates untouched interior markers
     *    (real-VFS-probe confirmed). Such a marker's LINE still exists (so it isn't an orphan) — drop its dead
     *    anchor + stranded card so reconcile rebuilds the card from `line_number`.
     * 4. **Bin the orphans** via [CommentStore.binFromPool] (unlinks the live pool file, keeps any tombstone —
     *    a reload can race a hook consume). Gone for the injector + peers.
     * 5. **Reconcile** every open editor to recreate the cards the reload disposed / we dropped. All editors of
     *    one file share this [Document] instance across a reload, so identity matches every split.
     * 6. **Sweep editorless anchors.** If the reload cleared the document's unsaved state and no editor of it is
     *    open, no save-on-close `beforeDocumentSaving` will run its deferred-anchor sweep — so retire the
     *    document's remaining anchors here (the writeback already ran), so a `RangeMarker` (which pins its
     *    `Document`) can't leak. Also mops up an anchor a *peer project's* manager binned from the shared store.
     *
     * Binning is **reload-only** (a save never runs steps 3-4: an in-IDE edit's writeback keeps the stored
     * `line_content` in step, so only an *external* change diverges) and **silent**. EDT-confined.
     */
    private fun onFileContentReloaded(document: Document) {
        // Detection is keyed on the durable STORE (via the document's file path), NOT the transient `anchors`
        // map: a reload can fire for a document whose editors all closed (their anchors were swept) or, later,
        // for a peer-synced comment never opened here — those have no anchor yet must still be considered.
        val path = localAbsPath(document)
        val comments = if (path == null) emptyList() else store.forAbsPath(path)
        if (comments.isNotEmpty()) {
            val presentLines = document.text.lineSequence().mapTo(HashSet()) { it.trim() }
            val orphaned = comments.filter { isOrphaned(it, presentLines) }.map { it.uuid }.toSet()
            // Markers this reload INVALIDATED whose comment is NOT an orphan (its line survives — a both-ends
            // edit killed an untouched interior marker): drop the dead anchor + stranded card so reconcile
            // re-anchors from line_number. (Anchors, not the store, since this is purely about the live marker.)
            val invalidated = anchors.entries
                .filter { it.value.document === document && !it.value.isValid && it.key !in orphaned }
                .map { it.key }.toSet()

            writeBackAnchors(document, skip = orphaned)

            invalidated.forEach { uuid ->
                anchors.remove(uuid)?.dispose()
                rendered.values.forEach { it.remove(uuid)?.dispose() }
            }

            orphaned.forEach { uuid ->
                store.binFromPool(uuid)
                anchors.remove(uuid)?.dispose()
            }
        }

        rendered.keys.filter { it.document === document }.forEach(::reconcile)
        sweepEditorlessAnchors(document)
    }

    /**
     * True if [comment] is orphaned by the reload — the signal for binning. Deliberately **conservative**,
     * because a mis-fire silently deletes a user's comment for every client, so the test is **content presence,
     * not marker position**: the comment is orphaned iff its stored `line_content` (trimmed) is no longer any
     * line in the reloaded file ([presentLines]).
     *
     * Why presence and not "the marker drifted": a real reload replays as one `DocumentImpl.replaceString` with
     * a common-affix trim, so whether the *deleted line's own* marker survives-and-shifts or is invalidated
     * depends on the surrounding text — an unreliable, input-dependent signal — and a comment whose line merely
     * *moved* would false-bin under a drift check. "Is the commented text still in the file?" is
     * marker-validity-independent and correct for every case: a deleted/rewritten line → text gone → orphan; a
     * line moved, or an interior line a both-ends edit invalidated the marker for → text still present → kept; a
     * re-indent → the trimmed text is present → kept. It also means a card re-anchored from a stale `line_number`
     * isn't re-examined for drift on the next reload (which would bin it a cycle late).
     *
     * Guards: a **non-pending** (Seen/consumed) comment is never binned (an agent resolving a comment consumes
     * it AND edits the line, so its drift is expected — keep its re-pend + `processed/` tombstone); a **blank**
     * baseline is uncomparable. Accepted false-negative: if the deleted line's trimmed text happens to match a
     * *different* surviving line (blank, `}`, boilerplate), the comment is kept on that line (safe-fail).
     */
    private fun isOrphaned(comment: HeviewComment, presentLines: Set<String>): Boolean {
        if (comment.status != CommentStatus.PENDING) return false
        val stored = comment.lineContent.trim()
        if (stored.isEmpty()) return false
        return stored !in presentLines
    }

    /**
     * Retire every anchor on [document] once no open editor shows it. A [RangeMarker] pins its `Document`, so a
     * reload or save that leaves the document editorless (a reload clears unsaved state, so no save-on-close
     * `beforeDocumentSaving` will run) must drop the document's remaining anchors or leak the `Document`. Also
     * mops up an anchor whose comment a *peer project's* manager already binned from the shared store (its
     * reconcile can't sweep an editorless document). Shared by the reload + save paths.
     */
    private fun sweepEditorlessAnchors(document: Document) {
        if (rendered.keys.any { it.document === document }) return
        anchors.entries.filter { it.value.document === document }
            .map { it.key }.toList()
            .forEach { anchors.remove(it)?.dispose() }
    }

    /**
     * [document] is being flushed to disk: write each of its comments' *current* line back to the pool
     * (cycle3 #2). The persisted `line_number`/`line_content` were frozen at submit, so after edits above a
     * comment the on-disk number goes stale — and the injector, a reopen, and the reload fallback all read
     * it. Saving is a correct sync point: the agent reads the file from disk, so the pool should match it
     * when the document lands on disk (an unsaved edit correctly leaves the pool alone — the agent still
     * sees the old file). See [writeBackAnchors].
     */
    private fun onBeforeDocumentSaving(document: Document) {
        writeBackAnchors(document)
        // Deferred-close cleanup: if the last editor of this document already closed, `forget` left its anchors
        // in place (the document was unsaved and this pending save-on-close still needed them for the writeback
        // above). Retire them now that the write has read them (shared with the reload path).
        sweepEditorlessAnchors(document)
    }

    /**
     * Write each valid anchor on [document] back to the pool as its comment's current `line_number` /
     * `line_content`. The live [RangeMarker] gives the current line; an invalidated marker is skipped (no
     * defensible line to record), as is any uuid in [skip]. The store no-ops when nothing changed and never
     * recreates a consumed thread. Shared by the save (`beforeDocumentSaving`) and reload
     * (`fileContentReloaded`) paths — in both, [document] equals disk. The reload path passes the orphans it is
     * about to bin as [skip], so this never persists a position for a comment that is being removed.
     */
    private fun writeBackAnchors(document: Document, skip: Set<String> = emptySet()) {
        anchors.entries
            .filter { it.value.document === document && it.value.isValid && it.key !in skip }
            .forEach { (uuid, marker) ->
                // The isValid filter guarantees an in-range startOffset, so getLineNumber needs no clamp.
                val line = document.getLineNumber(marker.startOffset)
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

        // Remove cards whose comment is gone (dropped from the map before dispose so onDispose sees no stale
        // entry). Then retire anchors on THIS document whose comment left the store — gated on STORE presence,
        // not card presence: a comment whose inlay the platform already disposed (card stripped from `cards`)
        // is still cleaned up on delete, while a reload-transient (comment still in the store, card momentarily
        // absent) keeps its shifted marker so trust-the-marker holds.
        cards.keys.filter { it !in desired }.forEach { uuid -> cards.remove(uuid)?.dispose() }
        anchors.entries
            .filter { (uuid, m) -> m.document === editor.document && store.get(uuid) == null }
            .map { it.key }.toList()
            .forEach { anchors.remove(it)?.dispose() }

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
                // If the platform disposes the inlay (its text range was replaced — a reload, or the line
                // was deleted), drop the stale entry so a later reconcile recreates the card. Do NOT retire
                // the shared anchor here: a reload disposes the inlay but leaves the marker valid+shifted,
                // and reconcile recreates the card immediately — retiring it would force currentLineEndOffset
                // back to the stale line_number, defeating trust-the-marker. A genuinely invalid marker is
                // disposed by currentLineEndOffset on recreation; a deleted comment / closed editor retire it
                // explicitly (reconcile's removal branch, forget).
                rendered[editor]?.values?.remove(disposed)
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
        cards.values.toList().forEach { it.dispose(preserveScroll = false) }
        // Retire markers whose Document no longer has ANY open editor (a RangeMarker pins its Document, so the
        // last editor of a file closing must drop its markers — including ones whose card the platform stripped
        // from `cards` via onDispose). Keyed on the Document, NOT "no card shows it", so a still-open split
        // whose inlay was transiently disposed keeps its live marker. BUT skip an **unsaved** document: closing
        // its last editor triggers a save-on-close whose `beforeDocumentSaving` still needs these anchors for
        // the line_number writeback, and editorReleased fires first — onBeforeDocumentSaving retires them once
        // it has written them (an unsaved doc that is never saved leaves the disk unchanged, so its stale
        // line_number stays consistent, and manager.dispose() sweeps the lingering anchors on project close).
        val fdm = FileDocumentManager.getInstance()
        anchors.entries
            .filter { (_, m) -> rendered.keys.none { it.document === m.document } && !fdm.isDocumentUnsaved(m.document) }
            .map { it.key }.toList()
            .forEach { anchors.remove(it)?.dispose() }
    }

    private fun isRelevant(editor: Editor): Boolean =
        editor.project == project &&
            editor.editorKind == EditorKind.MAIN_EDITOR &&
            localAbsPath(editor) != null

    /** This editor's file as an absolute OS path, or null if it isn't a local file. */
    private fun localAbsPath(editor: Editor): String? = localAbsPath(editor.document)

    /** [document]'s file as an absolute OS path, or null if it isn't a local file (no open editor needed). */
    private fun localAbsPath(document: Document): String? =
        FileDocumentManager.getInstance().getFile(document)
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
