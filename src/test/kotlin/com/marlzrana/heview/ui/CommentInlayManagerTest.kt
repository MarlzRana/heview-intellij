package com.marlzrana.heview.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.marlzrana.heview.model.CommentJson
import com.marlzrana.heview.model.newFileComment
import com.marlzrana.heview.storage.CommentStore
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Platform-fixture coverage for [CommentInlayManager]'s editor/store lifecycle — the increment's core
 * behavior, which the pure JUnit5 store tests can't see (addresses aeview finding [1]).
 *
 * The manager gates on local files (`isInLocalFileSystem`), so these use real temp files and real
 * [Editor]s created via [EditorFactory]. A fake [InlayCardHost] (injected through the manager's
 * test-only `hostFactory`) records live-card counts per editor, so the assertions test reconcile /
 * tracking / teardown without depending on the experimental component-inlay API or a visible editor.
 * The application [CommentStore] is replaced with a synchronous, temp-dir-backed instance so the real
 * `~/.heview` pool is never touched.
 */
class CommentInlayManagerTest : BasePlatformTestCase() {
    private lateinit var tempDir: Path
    private lateinit var store: CommentStore
    private lateinit var manager: CommentInlayManager
    private val hosts = HashMap<Editor, RecordingHost>()
    private val openedEditors = mutableListOf<Editor>()

    /** A host that counts live cards (and records the last placement offset) for one editor. */
    private class RecordingHost : InlayCardHost {
        var live = 0
            private set
        var lastOffset = -1
            private set
        private var last: Disposable? = null

        override fun addCardBelow(lineEndOffset: Int, card: JComponent): Disposable {
            live++
            lastOffset = lineEndOffset
            return Disposable { live-- }.also { last = it }
        }

        override fun disposeCard(card: Disposable) = Disposer.dispose(card)

        /** Simulate the platform disposing the most recently placed inlay (e.g. its text was deleted). */
        fun disposeLastReturned() = last?.let { Disposer.dispose(it) } ?: Unit
    }

    /** A host that always declines placement (addComponentInlay returning null). */
    private class DecliningHost : InlayCardHost {
        override fun addCardBelow(lineEndOffset: Int, card: JComponent): Disposable? = null
        override fun disposeCard(card: Disposable) = Disposer.dispose(card)
    }

    override fun setUp() {
        super.setUp()
        // toRealPath so the whitelisted root matches the VFS's canonical path (macOS /var → /private/var).
        tempDir = Files.createTempDirectory("heview-fixture").toRealPath()
        // The fixture guards VFS access to a few allowed roots; our temp files live outside them.
        VfsRootAccess.allowRootAccess(testRootDisposable, tempDir.toString())
        store = CommentStore(tempDir.resolve("comments"), runIo = { it.run() }, runEdt = { it.run() })
        // A dedicated manager instance (not the project-service singleton the real startup activity
        // drives) with an injected temp-dir store and fake host, so the test is fully isolated.
        manager = CommentInlayManager(project)
        Disposer.register(testRootDisposable, manager)
        manager.storeOverride = store
        manager.hostFactory = { editor -> hosts.getOrPut(editor) { RecordingHost() } }
    }

    override fun tearDown() {
        try {
            openedEditors.toList().forEach {
                if (!it.isDisposed) EditorFactory.getInstance().releaseEditor(it)
            }
            openedEditors.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testInitRendersCardOnAlreadyOpenEditorAndIsIdempotent() {
        val (editor, path) = openLocalEditor("A.txt", "line0\nline1\nline2\n")
        store.save(commentAt(path, line0Based = 0, content = "hi"))

        manager.init() // reopened-project path: editor was open before listeners were wired
        assertEquals(1, liveCards(editor))

        manager.init() // idempotent — no second card
        assertEquals(1, liveCards(editor))
    }

    fun testSplitGetsCardAndDeleteRemovesItFromEverySplit() {
        val (e1, path) = openLocalEditor("B.txt", "a\nb\nc\n")
        val comment = commentAt(path, line0Based = 1, content = "look here")
        store.save(comment)
        manager.init()
        assertEquals(1, liveCards(e1))

        val e2 = split(e1) // editorCreated → reconcile the new split
        assertEquals(1, liveCards(e2))
        assertEquals(1, liveCards(e1))

        store.delete(comment.uuid) // store change → reconcile every split
        assertEquals(0, liveCards(e1))
        assertEquals(0, liveCards(e2))
    }

    fun testReconcileDoesNotDuplicateAnExistingCard() {
        val (e1, path) = openLocalEditor("C.txt", "x\ny\n")
        store.save(commentAt(path, line0Based = 0, content = "one"))
        manager.init()
        assertEquals(1, liveCards(e1))

        // An unrelated change re-reconciles e1; the existing card must not be rendered twice.
        store.save(commentAt("/somewhere/else.txt", line0Based = 0, content = "other file"))
        assertEquals(1, liveCards(e1))
    }

    fun testReleasingAnEditorDisposesItsCards() {
        val (e1, path) = openLocalEditor("D.txt", "p\nq\n")
        store.save(commentAt(path, line0Based = 0, content = "bye"))
        manager.init()
        assertEquals(1, liveCards(e1))

        EditorFactory.getInstance().releaseEditor(e1)
        openedEditors.remove(e1)
        assertEquals(0, liveCards(e1)) // editorReleased → forget → dispose
    }

    fun testInitHydratesPoolFilesIntoOpenEditors() {
        val (editor, path) = openLocalEditor("G.txt", "a\nb\n")
        // Write a valid comment straight to the pool (not via store.save) so init() must hydrate() it.
        val uuid = "g1"
        Files.createDirectories(tempDir.resolve("comments"))
        Files.writeString(
            tempDir.resolve("comments").resolve("$uuid.json"),
            CommentJson.encode(commentAt(path, line0Based = 0, content = "from disk").copy(uuid = uuid)),
        )
        manager.init()
        assertEquals(1, liveCards(editor)) // hydrate → reconcile → rendered
    }

    fun testComposeRendersOneCardWithNoDuplicateAndSplitGetsItsOwn() {
        val (e1, _) = openLocalEditor("E.txt", "a\nb\n")
        manager.init()
        val thread = manager.compose(e1) ?: error("compose card was not placed")
        assertEquals(1, liveCards(e1)) // the compose card

        thread.submitForTest("my comment")
        assertEquals(1, liveCards(e1)) // save-triggered reconcile must not add a duplicate
        assertEquals(1, store.all().size) // persisted

        val e2 = split(e1)
        assertEquals(1, liveCards(e2)) // the split gets its own single card
    }

    fun testSplitPlacesCardAtLiveLineAfterEditAboveComment() {
        val (e1, path) = openLocalEditor("F.txt", "l0\nl1\nl2\nl3\n")
        store.save(commentAt(path, line0Based = 2, content = "on l2")) // line_number = 3
        manager.init()
        assertEquals(1, liveCards(e1))

        // Insert a line at the very top; the shared RangeMarker should follow it down one line.
        WriteCommandAction.runWriteCommandAction(project) { e1.document.insertString(0, "NEW\n") }

        val e2 = split(e1)
        val liveOffset = e1.document.getLineEndOffset(3) // where the comment lives now
        val staleOffset = e1.document.getLineEndOffset(2) // where the stale line_number would place it
        assertEquals(liveOffset, hosts.getValue(e2).lastOffset) // followed the edit, not the snapshot
        assertTrue(liveOffset != staleOffset)
    }

    fun testBlankSubmitPersistsNothingAndStaysInCompose() {
        val (e1, _) = openLocalEditor("H.txt", "a\n")
        manager.init()
        val thread = manager.compose(e1) ?: error("compose card was not placed")
        assertEquals(1, liveCards(e1))

        thread.submitForTest("   ") // settled decision: blank/whitespace is dropped
        assertEquals(0, store.all().size) // nothing persisted
        assertEquals(1, liveCards(e1)) // still the compose card, not flipped/duplicated
    }

    fun testTwoCommentsOnSameFileBothRenderAndDeletingOneLeavesTheOther() {
        val (e1, path) = openLocalEditor("J.txt", "a\nb\nc\n")
        val c1 = commentAt(path, line0Based = 0, content = "one")
        store.save(c1)
        store.save(commentAt(path, line0Based = 2, content = "two"))
        manager.init()
        assertEquals(2, liveCards(e1))

        store.delete(c1.uuid)
        assertEquals(1, liveCards(e1))
    }

    fun testPlatformDisposedDisplayCardIsRecreatedOnNextReconcile() {
        val (e1, path) = openLocalEditor("K.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "x")
        store.save(comment)
        manager.init()
        val host = hosts.getValue(e1)
        assertEquals(1, host.live)

        host.disposeLastReturned() // the platform disposes the inlay (e.g. its text range was deleted)
        assertEquals(0, host.live)

        store.save(comment) // any store change re-reconciles e1; the self-removed card must come back
        assertEquals(1, host.live)
    }

    fun testUnplaceableEditorTracksNothingAndLeaksNoAnchor() {
        val (e1, path) = openLocalEditor("L.txt", "a\n")
        manager.hostFactory = { DecliningHost() } // host cannot place a component inlay
        store.save(commentAt(path, line0Based = 0, content = "x"))
        manager.init() // reconcile → displayThread → startDisplay false → null, anchor retired

        assertNull(manager.compose(e1)) // compose also returns null when placement is declined
        assertEquals(0, manager.anchorCountForTest()) // no leaked RangeMarker on the unplaceable path
    }

    private fun liveCards(editor: Editor): Int = hosts[editor]?.live ?: 0

    private fun commentAt(absPath: String, line0Based: Int, content: String) =
        newFileComment(
            workspace = project.basePath ?: tempDir.toString(),
            absPath = absPath,
            line0Based = line0Based,
            lineContent = "",
            content = content,
            createdAt = "2026-01-01T00:00:00.000Z",
        )

    private fun openLocalEditor(name: String, text: String): Pair<Editor, String> {
        val file = tempDir.resolve(name)
        Files.writeString(file, text)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
            ?: error("could not locate $file in the VFS")
        val document = FileDocumentManager.getInstance().getDocument(vf) ?: error("no document for $file")
        val editor = EditorFactory.getInstance()
            .createEditor(document, project, vf, false, EditorKind.MAIN_EDITOR)
        openedEditors += editor
        return editor to vf.toNioPath().toString()
    }

    private fun split(editor: Editor): Editor {
        val vf = FileDocumentManager.getInstance().getFile(editor.document)!!
        val e = EditorFactory.getInstance()
            .createEditor(editor.document, project, vf, false, EditorKind.MAIN_EDITOR)
        openedEditors += e
        return e
    }
}
