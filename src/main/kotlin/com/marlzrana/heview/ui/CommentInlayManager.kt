package com.marlzrana.heview.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.marlzrana.heview.model.HeviewComment
import com.marlzrana.heview.model.newFileComment
import com.marlzrana.heview.storage.CommentStore
import com.marlzrana.heview.util.HeviewTime

/**
 * Owns the inlay cards for a project: it is the single controller that creates, tracks and disposes
 * every [CommentThread], mirroring reviewa's comment controller.
 *
 * Two triggers keep the on-screen cards in sync with the shared [CommentStore]:
 * - **Editor lifecycle** — an [EditorFactoryListener] renders display cards when an editor for a
 *   commented file opens (including splits and reopened projects) and tears them down on release.
 * - **Store changes** — a change listener reconciles every open editor so a comment added or deleted
 *   in one editor appears/disappears in every split showing the same file.
 *
 * The create flow is routed through [compose] (rather than living in the action) precisely so this
 * manager tracks the resulting thread before [CommentStore.save] fires: reconcile then sees the card
 * as already present and never renders a duplicate in the composing editor.
 *
 * EDT-confined: [init] is invoked on the EDT and every callback (editor events, store change, the
 * hydrate hop) reaches us on the EDT, so the [rendered] map needs no locking.
 */
@Service(Service.Level.PROJECT)
internal class CommentInlayManager(private val project: Project) : Disposable {
    private val store: CommentStore get() = service()
    private val author: String get() = System.getProperty("user.name") ?: "You"

    // Every relevant open editor -> its display cards keyed by comment uuid. An editor stays in the
    // map (even with an empty value) while open, so a later store change can render new comments
    // into an editor that currently shows none.
    private val rendered = HashMap<Editor, MutableMap<String, CommentThread>>()
    private var initialized = false

    /** Wire the listeners, render already-open editors, and hydrate the store. Idempotent; EDT-only. */
    fun init() {
        if (initialized) return
        initialized = true

        val subscription = store.addChangeListener { onStoreChanged() }
        Disposer.register(this, subscription)

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
    fun compose(editor: Editor) {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isInLocalFileSystem) return
        val workspace = project.basePath ?: return
        val absPath = file.toNioPath().toString()
        // The caret can sit in virtual space (past the last line); clamp so getLineEndOffset is safe.
        val line = editor.caretModel.logicalPosition.line
            .coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
        val lineEndOffset = document.getLineEndOffset(line)
        // Track the anchor line across edits made while composing; read it again at submit.
        val anchor = document.createRangeMarker(document.getLineStartOffset(line), lineEndOffset)

        lateinit var thread: CommentThread
        thread = CommentThread(
            project = project,
            host = ComponentInlayCardHost(editor),
            lineEndOffset = lineEndOffset,
            author = author,
            onDelete = { store.delete(it.uuid) },
            onSubmit = { text ->
                val rawLine = if (anchor.isValid) document.getLineNumber(anchor.startOffset) else line
                val anchorLine = rawLine.coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
                val lineContent = document.getText(
                    TextRange(document.getLineStartOffset(anchorLine), document.getLineEndOffset(anchorLine)),
                )
                val comment = newFileComment(
                    workspace = workspace,
                    absPath = absPath,
                    line0Based = anchorLine,
                    lineContent = lineContent,
                    content = text,
                    createdAt = HeviewTime.nowIso(),
                )
                // Track BEFORE save: save() fires the change listener synchronously, which reconciles
                // this editor — registering first means reconcile treats the card as already present
                // and won't render a duplicate. Other editors on this file still get their own card.
                track(editor, comment.uuid, thread)
                store.save(comment)
                comment
            },
            onDispose = {
                if (anchor.isValid) anchor.dispose()
                rendered[editor]?.values?.remove(thread)
            },
        )

        if (!thread.startCompose()) {
            if (anchor.isValid) anchor.dispose()
            thisLogger().warn("heview: inline comments are not available in this editor")
        }
    }

    private fun onStoreChanged() {
        // The map holds every open relevant editor, so this covers them all.
        rendered.keys.toList().forEach { reconcile(it) }
    }

    /** Bring [editor]'s display cards in line with the store: add missing, dispose deleted. */
    private fun reconcile(editor: Editor) {
        if (editor.isDisposed) {
            forget(editor)
            return
        }
        val path = FileDocumentManager.getInstance().getFile(editor.document)
            ?.takeIf { it.isInLocalFileSystem }
            ?.toNioPath()?.toString()
        val cards = rendered.getOrPut(editor) { LinkedHashMap() }
        val desired = if (path == null) emptyMap() else store.forAbsPath(path).associateBy { it.uuid }

        // Remove cards whose comment is gone. Drop from the map before disposing so the card's
        // onDispose never observes a stale entry.
        cards.keys.filter { it !in desired }.forEach { uuid -> cards.remove(uuid)?.dispose() }

        // Add cards for comments not yet shown in this editor.
        for ((uuid, comment) in desired) {
            if (cards.containsKey(uuid)) continue
            displayThread(editor, comment)?.let { cards[uuid] = it }
        }
    }

    private fun displayThread(editor: Editor, comment: HeviewComment): CommentThread? {
        val document = editor.document
        val line = (comment.lineNumber - 1).coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
        val lineEndOffset = document.getLineEndOffset(line)
        val thread = CommentThread(
            project = project,
            host = ComponentInlayCardHost(editor),
            lineEndOffset = lineEndOffset,
            author = author,
            onDelete = { store.delete(it.uuid) },
        )
        return if (thread.startDisplay(comment)) thread else null
    }

    private fun track(editor: Editor, uuid: String, thread: CommentThread) {
        rendered.getOrPut(editor) { LinkedHashMap() }[uuid] = thread
    }

    /** Drop tracking for [editor] and dispose its cards. No-op if the editor was never tracked. */
    private fun forget(editor: Editor) {
        val cards = rendered.remove(editor) ?: return
        cards.values.toList().forEach { it.dispose() }
    }

    private fun isRelevant(editor: Editor): Boolean =
        editor.project == project &&
            editor.editorKind == EditorKind.MAIN_EDITOR &&
            FileDocumentManager.getInstance().getFile(editor.document)?.isInLocalFileSystem == true

    override fun dispose() {
        rendered.keys.toList().forEach { forget(it) }
    }
}
