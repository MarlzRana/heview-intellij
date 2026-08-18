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
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.marlzrana.heview.model.CommentJson
import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.model.HeviewReply
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

    /** A host that counts live cards (and records the last placement offset + card) for one editor. */
    private class RecordingHost : InlayCardHost {
        var live = 0
            private set
        var lastOffset = -1
            private set
        private var last: Disposable? = null
        private var card: JComponent? = null

        override fun addCardBelow(lineEndOffset: Int, card: JComponent): Disposable {
            live++
            lastOffset = lineEndOffset
            this.card = card
            return Disposable { live-- }.also { last = it }
        }

        override fun disposeCard(card: Disposable) = Disposer.dispose(card)

        /** Simulate the platform disposing the most recently placed inlay (e.g. its text was deleted). */
        fun disposeLastReturned() = last?.let { Disposer.dispose(it) } ?: Unit

        /** All JLabel texts in the placed card — used to assert the Pending/Seen status chip. */
        fun labelTexts(): List<String> = card?.let(::collectLabelTexts) ?: emptyList()

        private fun collectLabelTexts(c: java.awt.Component): List<String> = buildList {
            if (c is javax.swing.JLabel) c.text?.let { add(it) }
            if (c is java.awt.Container) c.components.forEach { addAll(collectLabelTexts(it)) }
        }

        /** Tooltips of the icon action buttons in the placed card (Delete / Edit / Re-pend). */
        fun buttonTooltips(): List<String> = card?.let(::collectButtonTooltips) ?: emptyList()

        private fun collectButtonTooltips(c: java.awt.Component): List<String> = buildList {
            if (c is javax.swing.JButton) c.toolTipText?.let { add(it) }
            if (c is java.awt.Container) c.components.forEach { addAll(collectButtonTooltips(it)) }
        }

        /** Click the first icon button with [tooltip] — exercises the real Swing listener wiring. */
        fun clickButton(tooltip: String): Boolean {
            val button = card?.let { findButton(it, tooltip) } ?: return false
            button.doClick()
            return true
        }

        private fun findButton(c: java.awt.Component, tooltip: String): javax.swing.JButton? {
            if (c is javax.swing.JButton && c.toolTipText == tooltip) return c
            if (c is java.awt.Container) for (child in c.components) findButton(child, tooltip)?.let { return it }
            return null
        }
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
        // The save fires a synchronous reconcile while `displayed` is still null; refreshDisplay's
        // `displayed ?: return` guard skips it, so submit renders display exactly once (no double-render).
        assertEquals(1, thread.displayRenderCount)

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

    fun testFileContentReloadRecreatesCardsTheReloadDisposed() {
        val (e1, path) = openLocalEditor("RL.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "please fix")
        store.save(comment)
        manager.init()
        val host = hosts.getValue(e1)
        assertEquals(1, host.live)

        // An agent rewriting the file triggers a whole-document reload that disposes the inlay; unlike a
        // store change it fires no reconcile, so without the FileDocumentManagerListener it stays gone.
        host.disposeLastReturned()
        assertEquals(0, host.live)

        manager.simulateFileContentReloadedForTest(e1.document)
        assertEquals(1, host.live) // reconciled back into view
        assertEquals(1, manager.anchorCountForTest()) // one fresh anchor, no leak
    }

    fun testFileContentReloadDropsStaleAnchorsSoLaterSplitsUsePersistedLine() {
        val (e1, path) = openLocalEditor("RL2.txt", "l0\nl1\nl2\nl3\n")
        store.save(commentAt(path, line0Based = 2, content = "on l2")) // line_number = 3 (0-based 2)
        manager.init()
        assertEquals(1, liveCards(e1))

        // Drift the tracked marker: an insert up top moves "l2" (and its RangeMarker) down to line 3, so
        // a split opened right now would follow it (see testSplitPlacesCardAtLiveLineAfterEditAboveComment).
        WriteCommandAction.runWriteCommandAction(project) { e1.document.insertString(0, "NEW\n") }

        // A reload wholesale-replaces the buffer, so the drifted marker is unreliable: the handler drops
        // it and cards fall back to the persisted line_number, the line a freshly opened editor would use.
        manager.simulateFileContentReloadedForTest(e1.document)

        val e2 = split(e1) // a split created AFTER the reload rebuilds this file's anchor
        val persistedOffset = e1.document.getLineEndOffset(2) // rebuilt from persisted line_number
        val driftedOffset = e1.document.getLineEndOffset(3) // where the un-dropped marker would have placed it
        assertEquals(persistedOffset, hosts.getValue(e2).lastOffset)
        assertTrue(persistedOffset != driftedOffset)
    }

    fun testSavingWritesTheMovedLineBackToTheStore() {
        val (e1, path) = openLocalEditor("WB.txt", "l0\nl1\nl2\nl3\n")
        val comment = commentAt(path, line0Based = 2, content = "on l2") // line_number = 3
        store.save(comment)
        manager.init()

        // An in-IDE edit above the comment moves the tracked marker down one line.
        WriteCommandAction.runWriteCommandAction(project) { e1.document.insertString(0, "NEW\n") }

        // The document is being flushed to disk → the durable anchor is rewritten to match the on-disk file.
        manager.simulateBeforeDocumentSavingForTest(e1.document)

        assertEquals(4, store.get(comment.uuid)?.lineNumber) // "l2" is now line 4 (1-based)
        assertEquals("l2", store.get(comment.uuid)?.lineContent) // and its current text
    }

    fun testUnsavedEditDoesNotChangeThePersistedLine() {
        val (e1, path) = openLocalEditor("WB2.txt", "l0\nl1\nl2\nl3\n")
        val comment = commentAt(path, line0Based = 2, content = "on l2") // line_number = 3
        store.save(comment)
        manager.init()

        // Edit above the comment but DON'T save: the agent still reads the old on-disk file, so the pool
        // must keep the old line_number (the writeback is save-gated, not edit-gated).
        WriteCommandAction.runWriteCommandAction(project) { e1.document.insertString(0, "NEW\n") }

        assertEquals(3, store.get(comment.uuid)?.lineNumber) // unchanged until a save fires
    }

    fun testUnplaceableEditorTracksNothingAndLeaksNoAnchor() {
        val (e1, path) = openLocalEditor("L.txt", "a\n")
        manager.hostFactory = { DecliningHost() } // host cannot place a component inlay
        store.save(commentAt(path, line0Based = 0, content = "x"))
        manager.init() // reconcile → displayThread → startDisplay false → null, anchor retired

        assertNull(manager.compose(e1)) // compose also returns null when placement is declined
        assertEquals(0, manager.anchorCountForTest()) // no leaked RangeMarker on the unplaceable path
    }

    fun testMarkingProcessedRelabelsTheCardToSeenInPlace() {
        val (e1, path) = openLocalEditor("M.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "please fix")
        store.save(comment)
        manager.init()
        val host = hosts.getValue(e1)
        assertEquals(1, host.live)
        assertTrue(host.labelTexts().contains("Pending"))
        assertFalse(host.labelTexts().contains("Seen"))

        // The consumption watcher's Seen flip: markProcessed fires a store change → reconcile →
        // refreshDisplay updates the existing card in place (no remove, no duplicate).
        store.markProcessed(comment.uuid)
        assertEquals(1, host.live)
        assertTrue(host.labelTexts().contains("Seen"))
        assertFalse(host.labelTexts().contains("Pending"))
    }

    fun testRefreshDisplaySkipsUnrelatedChangesAndRebuildsOnStatusFlip() {
        val (e1, path) = openLocalEditor("N.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "look")
        store.save(comment)
        manager.init()
        val card = manager.cardForTest(e1, comment.uuid) ?: error("no card")
        val baseline = card.displayRenderCount // rendered once on display

        // An unrelated change (a comment on a different file) fires a GLOBAL reconcile; the guard in
        // refreshDisplay must skip rebuilding this unchanged card (no flicker / re-disabled Delete).
        store.save(commentAt("/elsewhere/Other.txt", line0Based = 0, content = "other"))
        assertEquals(baseline, card.displayRenderCount)

        // A relevant change (status flip to Seen) must rebuild it in place.
        store.markProcessed(comment.uuid)
        assertEquals(baseline + 1, card.displayRenderCount)
    }

    fun testTwoReplyThreadRendersBothRowsInOneCard() {
        val (e1, path) = openLocalEditor("P.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "a").copy(
            replies = listOf(
                HeviewReply("a", CommentStatus.PENDING, "tester", "2026-01-01T00:00:00.000Z"),
                HeviewReply("b", CommentStatus.PROCESSED, "tester", "2026-01-01T00:00:00.000Z"),
            ),
        )
        store.save(comment)
        manager.init()

        val card = manager.cardForTest(e1, comment.uuid) ?: error("no card")
        assertEquals(1, liveCards(e1)) // one card…
        assertEquals(listOf(CommentStatus.PENDING, CommentStatus.PROCESSED), card.replyStatusesForTest()) // …two rows
        val labels = hosts.getValue(e1).labelTexts()
        assertTrue(labels.contains("Pending"))
        assertTrue(labels.contains("Seen"))
    }

    fun testConsumedThreadOffersPerReplyRependAndRependingRestoresPendingInPlace() {
        val (e1, path) = openLocalEditor("Q.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "please fix")
        store.save(comment)
        manager.init()
        val card = manager.cardForTest(e1, comment.uuid) ?: error("no card")
        assertEquals(listOf(CommentStatus.PENDING), card.replyStatusesForTest())

        store.markProcessed(comment.uuid) // the consumption watcher flips the whole thread to Seen
        assertEquals(listOf(CommentStatus.PROCESSED), card.replyStatusesForTest())
        assertTrue(hosts.getValue(e1).labelTexts().contains("Seen"))

        card.rependReplyForTest(0) // click the reply's re-pend clock
        assertEquals(CommentStatus.PENDING, store.get(comment.uuid)?.replies?.get(0)?.status)
        assertEquals(1, liveCards(e1)) // relabeled in place — no duplicate, no removal
        assertEquals(listOf(CommentStatus.PENDING), card.replyStatusesForTest())
        assertTrue(hosts.getValue(e1).labelTexts().contains("Pending"))
    }

    fun testEditReplyUpdatesContentAndRefreshesTheCardInPlace() {
        val (e1, path) = openLocalEditor("R.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "old text")
        store.save(comment)
        manager.init()
        val card = manager.cardForTest(e1, comment.uuid) ?: error("no card")
        val before = card.displayRenderCount

        card.editReplyForTest(0, "new text")

        assertEquals("new text", store.get(comment.uuid)?.replies?.get(0)?.content)
        assertEquals(1, liveCards(e1)) // same card, no duplicate
        assertTrue(card.displayRenderCount > before) // rebuilt in place with the new content
        assertFalse(card.isEditingForTest()) // returned to display mode (editingIndex cleared before firing)
    }

    fun testReplyRowsExposeTheCorrectActionsPerStatus() {
        val (e1, path) = openLocalEditor("W.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "x").copy(
            replies = listOf(
                HeviewReply("pending one", CommentStatus.PENDING, "tester", "2026-01-01T00:00:00.000Z"),
                HeviewReply("seen one", CommentStatus.PROCESSED, "tester", "2026-01-01T00:00:00.000Z"),
            ),
        )
        store.save(comment)
        manager.init()

        val tips = hosts.getValue(e1).buttonTooltips()
        // Two rows: Pending → Delete+Edit; Seen → Delete+Edit+Re-pend (re-pend only on the Seen row).
        assertEquals(2, tips.count { it == "Delete" })
        assertEquals(2, tips.count { it == "Edit" })
        assertEquals(1, tips.count { it == "Re-pend" })
    }

    fun testEditSurvivesAConcurrentConsumeViaStableReplyId() {
        val (e1, path) = openLocalEditor("V.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "original")
        store.save(comment)
        manager.init()
        val card = manager.cardForTest(e1, comment.uuid) ?: error("no card")
        card.startEditForTest(0) // the user begins editing reply 0
        store.markProcessed(comment.uuid) // a hook consumes the whole thread mid-edit (reply 0 → Seen)

        card.editReplyForTest(0, "edited") // the user saves the edit

        val reply0 = store.get(comment.uuid)?.replies?.get(0)
        assertEquals("edited", reply0?.content) // applied despite the concurrent consume — matched by id
        assertEquals(CommentStatus.PENDING, reply0?.status) // and revived the consumed reply
        assertFalse(card.isEditingForTest()) // the card returned to display mode (not stuck)
    }

    fun testReplyAddsARowAndRefreshesTheCardInPlace() {
        val (e1, path) = openLocalEditor("S.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "first")
        store.save(comment)
        manager.init()
        val card = manager.cardForTest(e1, comment.uuid) ?: error("no card")

        card.replyForTest("second")

        assertEquals(listOf("first", "second"), store.get(comment.uuid)?.replies?.map { it.content })
        assertEquals("first\n\nsecond", store.get(comment.uuid)?.content) // derived content, blank-line join
        assertEquals(1, liveCards(e1)) // still one card (now two rows)
    }

    fun testInlineEditSuppressesRefreshOnAnUnrelatedReconcile() {
        val (e1, path) = openLocalEditor("T.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "original")
        store.save(comment)
        manager.init()
        val card = manager.cardForTest(e1, comment.uuid) ?: error("no card")
        card.startEditForTest(0) // open the inline edit without submitting
        val baseline = card.displayRenderCount

        // A GLOBAL reconcile from an unrelated change must NOT rebuild this card — that would clobber the
        // in-progress edit (the editingIndex guard in refreshDisplay).
        store.save(commentAt("/elsewhere/Other.txt", line0Based = 0, content = "unrelated"))

        assertEquals(baseline, card.displayRenderCount)
    }

    fun testDeleteReplyThroughTheManagerRemovesItThenTheThread() {
        val (e1, path) = openLocalEditor("U.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "first")
        store.save(comment)
        manager.init()
        manager.cardForTest(e1, comment.uuid)!!.replyForTest("second") // two replies
        assertEquals(2, store.get(comment.uuid)?.replies?.size)

        manager.cardForTest(e1, comment.uuid)!!.deleteReplyForTest(0) // delete "first"
        assertEquals(listOf("second"), store.get(comment.uuid)?.replies?.map { it.content })
        assertEquals(1, liveCards(e1)) // card stays (one reply remains)

        manager.cardForTest(e1, comment.uuid)!!.deleteReplyForTest(0) // delete the last reply
        assertNull(store.get(comment.uuid)) // thread gone
        assertEquals(0, liveCards(e1)) // …and its card removed
    }

    fun testClickingTheDeleteIconWiresThroughToTheStore() {
        val (e1, path) = openLocalEditor("X.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "first")
        store.save(comment)
        manager.init()
        manager.cardForTest(e1, comment.uuid)!!.replyForTest("second") // two replies
        assertEquals(2, store.get(comment.uuid)?.replies?.size)

        // Delete starts disabled (double-click guard); flush the enable-later before clicking it.
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(hosts.getValue(e1).clickButton("Delete")) // a real click on the first row's Delete icon
        assertEquals(1, store.get(comment.uuid)?.replies?.size) // listener wired through → a reply removed
    }

    fun testDoubleClickingDeleteRemovesOnlyOneReply() {
        val (e1, path) = openLocalEditor("X2.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "first")
        store.save(comment)
        manager.init()
        manager.cardForTest(e1, comment.uuid)!!.replyForTest("second")
        manager.cardForTest(e1, comment.uuid)!!.replyForTest("third") // three replies
        assertEquals(3, store.get(comment.uuid)?.replies?.size)

        // Simulate a double-click WITHOUT flushing between: the first click deletes + rebuilds; the second
        // press lands on the freshly-rendered (still-disabled) Delete and must be a no-op.
        val host = hosts.getValue(e1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() // enable the initial Delete
        host.clickButton("Delete") // first click → deletes one, rebuilds (new Delete starts disabled)
        host.clickButton("Delete") // second click of the double → the new Delete is disabled → no-op

        assertEquals(2, store.get(comment.uuid)?.replies?.size) // exactly one removed, not two
    }

    fun testClickingTheRependIconWiresThroughToTheStore() {
        val (e1, path) = openLocalEditor("Z.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "x")
        store.save(comment)
        manager.init()
        store.markProcessed(comment.uuid) // Seen → the row shows the Re-pend clock
        assertEquals(CommentStatus.PROCESSED, store.get(comment.uuid)?.status)

        assertTrue(hosts.getValue(e1).clickButton("Re-pend")) // a real click on the Re-pend icon
        assertEquals(CommentStatus.PENDING, store.get(comment.uuid)?.status) // wired through → re-pended
    }

    fun testReplyDraftSurvivesARebuildAndClearsOnSubmit() {
        val (e1, path) = openLocalEditor("D1.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "first")
        store.save(comment)
        manager.init()
        val card = manager.cardForTest(e1, comment.uuid) ?: error("no card")
        card.setReplyDraftForTest("half-typed reply")

        store.markProcessed(comment.uuid) // an unrelated change rebuilds the card
        assertEquals("half-typed reply", card.replyDraftForTest()) // draft carried across the rebuild

        card.replyForTest("done") // submitting a reply
        assertEquals("", card.replyDraftForTest()) // clears the box (not re-seeded on the ensuing rebuild)
    }

    fun testSavingAnEditWhoseReplyWasDeletedLeavesEditMode() {
        val (e1, path) = openLocalEditor("D2.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "first")
        store.save(comment)
        manager.init()
        val card = manager.cardForTest(e1, comment.uuid) ?: error("no card")
        card.replyForTest("second") // two replies
        val reply0 = store.get(comment.uuid)!!.replies!![0]
        card.startEditForTest(0)
        store.deleteReply(comment.uuid, reply0) // the edited reply is deleted; the thread keeps "second"

        card.submitEditForTest(reply0, "edited") // save an edit that now matches nothing

        assertFalse(card.isEditingForTest()) // no-op save still returns to display mode
        assertEquals(listOf("second"), store.get(comment.uuid)?.replies?.map { it.content }) // unchanged
    }

    fun testCancellingAnInlineEditReturnsToDisplayShowingTheLatest() {
        val (e1, path) = openLocalEditor("Y.txt", "a\nb\n")
        val comment = commentAt(path, line0Based = 0, content = "original")
        store.save(comment)
        manager.init()
        val card = manager.cardForTest(e1, comment.uuid) ?: error("no card")
        card.startEditForTest(0)
        assertTrue(card.isEditingForTest())

        store.markProcessed(comment.uuid) // a consume lands mid-edit (displayed advances, rebuild deferred)
        card.cancelEditForTest()

        assertFalse(card.isEditingForTest()) // back in display mode
        assertEquals("original", store.get(comment.uuid)?.replies?.get(0)?.content) // cancel discarded input
        assertTrue(hosts.getValue(e1).labelTexts().contains("Seen")) // rendered the advanced (Seen) thread
    }

    private fun liveCards(editor: Editor): Int = hosts[editor]?.live ?: 0

    private fun commentAt(absPath: String, line0Based: Int, content: String) =
        newFileComment(
            workspace = project.basePath ?: tempDir.toString(),
            absPath = absPath,
            line0Based = line0Based,
            lineContent = "",
            content = content,
            author = "tester",
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
