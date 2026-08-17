package com.marlzrana.heview.storage

import com.marlzrana.heview.model.CommentJson
import com.marlzrana.heview.model.CommentStatus
import com.marlzrana.heview.model.HeviewReply
import com.marlzrana.heview.sampleComment
import com.marlzrana.heview.sampleReply
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class CommentStoreTest {
    // Synchronous executors so the async disk + EDT-hop paths run inline and deterministically; a fixed
    // default author so a legacy-file (no `replies`) reconstruction is deterministic.
    private fun store(dir: Path, author: String = "tester") =
        CommentStore(dir, runIo = { it.run() }, runEdt = { it.run() }, defaultAuthor = { author })

    private fun seed(dir: Path, comment: com.marlzrana.heview.model.HeviewComment) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("${comment.uuid}.json"), CommentJson.encode(comment))
    }

    /** Make [dir] non-writable; skip the test if the platform doesn't enforce it (e.g. running as root). */
    private fun makeReadOnlyOrSkip(dir: Path) {
        dir.toFile().setWritable(false)
        val enforced = try {
            Files.createFile(dir.resolve(".probe"))
            Files.deleteIfExists(dir.resolve(".probe"))
            false
        } catch (e: IOException) {
            true
        }
        assumeTrue(enforced, "read-only directory not enforced (running as root?)")
    }

    @Test
    fun `save writes a file and indexes the record`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1"))
        assertTrue(Files.exists(dir.resolve("u1.json")))
        assertEquals("u1", store.get("u1")?.uuid)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `save persists the exact shared-contract JSON`(@TempDir dir: Path) {
        val original = sampleComment(uuid = "u1")
        store(dir).save(original)
        val onDisk = CommentJson.decode(Files.readString(dir.resolve("u1.json")))
        assertEquals(original, onDisk)
    }

    @Test
    fun `save creates the comments directory if it does not exist`(@TempDir dir: Path) {
        val nested = dir.resolve("does/not/exist/comments")
        store(nested).save(sampleComment(uuid = "u1"))
        assertTrue(Files.exists(nested.resolve("u1.json")))
    }

    @Test
    fun `pending and processed counts reflect statuses`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "p1", status = CommentStatus.PENDING))
        store.save(sampleComment(uuid = "p2", status = CommentStatus.PENDING))
        store.save(sampleComment(uuid = "d1", status = CommentStatus.PROCESSED))
        assertEquals(2, store.pendingCount())
        assertEquals(1, store.processedCount())
    }

    @Test
    fun `save upserts an existing record in place`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1", status = CommentStatus.PENDING))
        store.save(sampleComment(uuid = "u1", status = CommentStatus.PROCESSED))
        assertEquals(1, store.all().size)
        assertEquals(CommentStatus.PROCESSED, store.get("u1")?.status)
    }

    @Test
    fun `delete removes the file and the record`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1"))
        store.delete("u1")
        assertFalse(Files.exists(dir.resolve("u1.json")))
        assertNull(store.get("u1"))
    }

    @Test
    fun `delete of an already-consumed (absent) file still drops the record`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1"))
        Files.delete(dir.resolve("u1.json")) // simulate a coding-agent hook consuming it
        store.delete("u1")
        assertNull(store.get("u1"))
    }

    @Test
    fun `upsert replaces the on-disk file`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1", status = CommentStatus.PENDING))
        store.save(sampleComment(uuid = "u1", status = CommentStatus.PROCESSED))
        val onDisk = CommentJson.decode(Files.readString(dir.resolve("u1.json")))
        assertEquals(CommentStatus.PROCESSED, onDisk.status)
    }

    @Test
    fun `delete of an unknown uuid is a no-op and fires no change`(@TempDir dir: Path) {
        val store = store(dir)
        var count = 0
        store.addChangeListener { count++ }
        store.delete("missing")
        assertEquals(0, count)
    }

    @Test
    fun `change listener fires on save and delete`(@TempDir dir: Path) {
        val store = store(dir)
        var count = 0
        store.addChangeListener { count++ }
        store.save(sampleComment(uuid = "u1"))
        store.delete("u1")
        assertEquals(2, count)
    }

    @Test
    fun `hydrate loads existing pool files into the index and fires one change`(@TempDir dir: Path) {
        seed(dir, sampleComment(uuid = "a"))
        seed(dir, sampleComment(uuid = "b"))
        val store = store(dir)
        var count = 0
        store.addChangeListener { count++ }
        store.hydrate()
        assertEquals(2, store.all().size)
        assertNotNull(store.get("a"))
        assertNotNull(store.get("b"))
        assertEquals(1, count)
    }

    @Test
    fun `hydrate skips unreadable files and loads the rest`(@TempDir dir: Path) {
        seed(dir, sampleComment(uuid = "ok"))
        Files.writeString(dir.resolve("broken.json"), "{ not valid json")
        val store = store(dir)
        store.hydrate()
        assertEquals(1, store.all().size)
        assertNotNull(store.get("ok"))
    }

    @Test
    fun `hydrate does not overwrite a record already held in memory`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1", status = CommentStatus.PROCESSED))
        // A stale on-disk copy for the same uuid; the in-memory record must win.
        seed(dir, sampleComment(uuid = "u1", status = CommentStatus.PENDING))
        store.hydrate()
        assertEquals(1, store.all().size)
        assertEquals(CommentStatus.PROCESSED, store.get("u1")?.status)
    }

    @Test
    fun `hydrate runs at most once`(@TempDir dir: Path) {
        val store = store(dir)
        store.hydrate()
        seed(dir, sampleComment(uuid = "late"))
        store.hydrate() // second call is a no-op — the pool is not re-read
        assertNull(store.get("late"))
    }

    @Test
    fun `hydrate on a missing directory is a no-op`(@TempDir dir: Path) {
        val store = store(dir.resolve("nope"))
        store.hydrate()
        assertEquals(0, store.all().size)
    }

    @Test
    fun `hydrate fires no change when every on-disk record is already present`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1")) // also writes u1.json (sync IO)
        var count = 0
        store.addChangeListener { count++ }
        store.hydrate()
        assertEquals(0, count) // nothing new loaded ⇒ no reconcile churn on startup
    }

    @Test
    fun `hydrate skips a file whose json uuid does not match its filename (path traversal)`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        // Filename is benign, but the payload uuid would escape commentsDir via fileFor on delete.
        Files.writeString(dir.resolve("benign.json"), CommentJson.encode(sampleComment(uuid = "../../evil")))
        val store = store(dir)
        store.hydrate()
        assertEquals(0, store.all().size)
        assertNull(store.get("../../evil"))
        assertNull(store.get("benign"))
    }

    @Test
    fun `hydrate skips schema-incomplete json`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("empty.json"), "{}")
        Files.writeString(dir.resolve("partial.json"), """{"uuid":"partial","content":"hi"}""")
        val store = store(dir)
        store.hydrate()
        assertEquals(0, store.all().size) // null required fields would NPE reconcile if indexed
    }

    @Test
    fun `hydrate skips a literal-null json file and still loads valid neighbors`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("nul.json"), "null") // Gson returns null without throwing
        seed(dir, sampleComment(uuid = "good"))
        val store = store(dir)
        store.hydrate()
        // The null file must not abort hydration for the whole pool.
        assertEquals(listOf("good"), store.all().map { it.uuid })
    }

    @Test
    fun `hydrate skips records with an unknown enum value`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        seed(dir, sampleComment(uuid = "ok"))
        Files.writeString(
            dir.resolve("badstatus.json"),
            CommentJson.encode(sampleComment(uuid = "badstatus")).replace("\"pending\"", "\"frobnicated\""),
        )
        Files.writeString(
            dir.resolve("badside.json"),
            CommentJson.encode(sampleComment(uuid = "badside")).replace("\"file\"", "\"sideways\""),
        )
        val store = store(dir)
        store.hydrate()
        assertEquals(listOf("ok"), store.all().map { it.uuid }) // unknown enum → null field → skipped
    }

    @Test
    fun `hydrate skips a record with a non-positive line number`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("zero.json"),
            CommentJson.encode(sampleComment(uuid = "zero")).replace("\"line_number\": 42", "\"line_number\": 0"),
        )
        val store = store(dir)
        store.hydrate()
        assertNull(store.get("zero"))
        assertEquals(0, store.all().size)
    }

    @Test
    fun `hydrate does not load processed comments`(@TempDir dir: Path) {
        seed(dir, sampleComment(uuid = "p", status = CommentStatus.PROCESSED))
        seed(dir, sampleComment(uuid = "q", status = CommentStatus.PENDING))
        val store = store(dir)
        store.hydrate()
        assertEquals(listOf("q"), store.all().map { it.uuid }) // plan §5: processed never resurrects
    }

    @Test
    fun `disposing a change-listener handle stops its callbacks but not the others`(@TempDir dir: Path) {
        val store = store(dir)
        var a = 0
        var b = 0
        val handleA = store.addChangeListener { a++ }
        store.addChangeListener { b++ }
        store.save(sampleComment(uuid = "u1"))
        handleA.dispose()
        store.save(sampleComment(uuid = "u2"))
        assertEquals(1, a) // stopped after dispose
        assertEquals(2, b) // still firing
    }

    @Test
    fun `a listener may unregister itself during fireChanged without a CME`(@TempDir dir: Path) {
        val store = store(dir)
        var self = 0
        var peer = 0
        lateinit var handle: com.intellij.openapi.Disposable
        handle = store.addChangeListener {
            self++
            handle.dispose() // reentrant removal during the fire
        }
        store.addChangeListener { peer++ }
        store.save(sampleComment(uuid = "u1")) // must not throw ConcurrentModificationException
        store.save(sampleComment(uuid = "u2"))
        assertEquals(1, self) // fired once, then removed itself
        assertEquals(2, peer) // untouched
    }

    @Test
    fun `forAbsPath matches when the stored abs_path is non-canonical`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(
            sampleComment(uuid = "c").copy(
                absPath = "/repo/src/../src/Foo.kt",
                logicalAbsPath = "/repo/src/../src/Foo.kt",
            ),
        )
        // Both sides normalize, so a foreign writer's non-canonical spelling still matches.
        assertEquals(listOf("c"), store.forAbsPath("/repo/src/Foo.kt").map { it.uuid })
    }

    @Test
    fun `forAbsPath returns only comments for that file, matched normalized`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "here")) // abs_path = /repo/src/Foo.kt
        store.save(
            sampleComment(uuid = "elsewhere").copy(absPath = "/repo/src/Bar.kt", logicalAbsPath = "/repo/src/Bar.kt"),
        )
        assertEquals(listOf("here"), store.forAbsPath("/repo/src/Foo.kt").map { it.uuid })
        // A non-normalized spelling of the same path still matches.
        assertEquals(listOf("here"), store.forAbsPath("/repo/./src/Foo.kt").map { it.uuid })
        assertTrue(store.forAbsPath("/repo/src/Nope.kt").isEmpty())
    }

    @Test
    fun `save retains the record and writes nothing when the directory is not writable`(@TempDir dir: Path) {
        val ro = Files.createDirectories(dir.resolve("ro"))
        makeReadOnlyOrSkip(ro)
        try {
            val store = store(ro)
            store.save(sampleComment(uuid = "u1"))
            assertNotNull(store.get("u1"))                       // record retained in memory
            assertFalse(Files.exists(ro.resolve("u1.json")))     // nothing persisted
            Files.list(ro).use { assertEquals(0L, it.count()) }  // no stray .json.tmp left behind
        } finally {
            ro.toFile().setWritable(true)
        }
    }

    @Test
    fun `delete removes the record even if the on-disk file cannot be deleted`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1"))
        makeReadOnlyOrSkip(dir)
        try {
            // reviewa parity: the thread leaves the UI regardless; the unlink is best-effort.
            store.delete("u1")
            assertNull(store.get("u1"))
        } finally {
            dir.toFile().setWritable(true)
        }
    }

    @Test
    fun `markProcessed flips status in memory, fires, and does not rewrite the pool file`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1")) // persists status=pending
        var fires = 0
        store.addChangeListener { fires++ }

        store.markProcessed("u1")

        assertEquals(CommentStatus.PROCESSED, store.get("u1")?.status) // Seen, still indexed
        assertEquals(1, fires)
        // The consumption watcher marks Seen AFTER the hook already moved the file out of the pool; a
        // rewrite here would resurrect an injectable duplicate. The on-disk file is left untouched.
        assertEquals(CommentStatus.PENDING, CommentJson.decode(Files.readString(dir.resolve("u1.json"))).status)
    }

    @Test
    fun `markProcessed is a no-op for an unknown or already-processed comment`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1"))
        store.markProcessed("u1")
        var fires = 0
        store.addChangeListener { fires++ }

        store.markProcessed("u1")     // already processed
        store.markProcessed("ghost")  // never existed

        assertEquals(0, fires)
        assertEquals(CommentStatus.PROCESSED, store.get("u1")?.status)
    }

    @Test
    fun `evict drops the record and fires without unlinking the file`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1"))
        var fires = 0
        store.addChangeListener { fires++ }

        store.evict("u1")

        assertNull(store.get("u1"))
        assertEquals(1, fires)
        // A peer/user already removed the pool file; evict must not schedule its own delete (unlike delete()).
        assertTrue(Files.exists(dir.resolve("u1.json")))
    }

    @Test
    fun `evict is a no-op for an unknown uuid so a self-delete stays idempotent`(@TempDir dir: Path) {
        val store = store(dir)
        var fires = 0
        store.addChangeListener { fires++ }

        store.evict("ghost")

        assertEquals(0, fires)
    }

    @Test
    fun `hydrate ignores comments nested under the processed subdirectory`(@TempDir dir: Path) {
        val store = store(dir)
        seed(dir, sampleComment(uuid = "root1")) // a real pending comment in the pool root
        // A pending-status file under comments/processed/ (a best-effort tombstone rewrite that stayed
        // pending): the pool's *.json listing is non-recursive, so it must NOT hydrate as pending — else
        // a later save() would resurrect an injectable comments/<uuid>.json.
        val processed = Files.createDirectories(dir.resolve("processed"))
        Files.writeString(processed.resolve("tomb1.json"), CommentJson.encode(sampleComment(uuid = "tomb1")))

        store.hydrate()

        assertEquals(listOf("root1"), store.all().map { it.uuid })
    }

    @Test
    fun `whenHydrated fires the callback once hydrate applies`(@TempDir dir: Path) {
        val store = store(dir)
        var ran = 0
        store.whenHydrated { ran++ }
        assertEquals(0, ran) // not hydrated yet

        store.hydrate()

        assertEquals(1, ran) // fired once the snapshot applied
    }

    @Test
    fun `whenHydrated fires immediately when hydrate has already applied`(@TempDir dir: Path) {
        val store = store(dir)
        store.hydrate()
        var ran = 0

        store.whenHydrated { ran++ }

        assertEquals(1, ran)
    }

    @Test
    fun `isPersisted tracks a successful save and clears on evict and delete`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1"))
        assertTrue(store.isPersisted("u1")) // write succeeded → on disk

        store.evict("u1")
        assertFalse(store.isPersisted("u1"))

        store.save(sampleComment(uuid = "u2"))
        store.delete("u2")
        assertFalse(store.isPersisted("u2"))
    }

    @Test
    fun `isPersisted is false when the save write fails`(@TempDir dir: Path) {
        val ro = Files.createDirectories(dir.resolve("ro"))
        makeReadOnlyOrSkip(ro)
        try {
            val store = store(ro)
            store.save(sampleComment(uuid = "u1"))
            assertNotNull(store.get("u1")) // retained in memory…
            assertFalse(store.isPersisted("u1")) // …but never persisted (the write failed)
        } finally {
            ro.toFile().setWritable(true)
        }
    }

    @Test
    fun `hydrate marks loaded comments persisted`(@TempDir dir: Path) {
        seed(dir, sampleComment(uuid = "u1"))
        val store = store(dir)
        store.hydrate()
        assertTrue(store.isPersisted("u1")) // it was read from disk
    }

    // ---- per-reply state machine (plan.html §5) ----

    private val laterTs = "2026-08-16T09:30:00.000Z"

    @Test
    fun `addReply appends a pending reply, recomputes the content, and re-persists`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1", replies = listOf(sampleReply(content = "first"))))

        store.addReply("u1", "second", "marlzrana", laterTs)

        val updated = store.get("u1")!!
        assertEquals(2, updated.replies?.size)
        assertEquals("first\n\nsecond", updated.content) // derived: both pending, blank-line joined
        assertEquals(CommentStatus.PENDING, updated.status)
        assertEquals(laterTs, updated.createdAt)
        assertEquals(HeviewReply("second", CommentStatus.PENDING, "marlzrana", laterTs), updated.replies?.last())
        assertEquals(updated, CommentJson.decode(Files.readString(dir.resolve("u1.json")))) // written back
        assertTrue(store.isPersisted("u1"))
    }

    @Test
    fun `editReply replaces a reply's text and revives it to pending`(@TempDir dir: Path) {
        val store = store(dir)
        val r0 = sampleReply(content = "old", status = CommentStatus.PROCESSED)
        store.save(sampleComment(uuid = "u1", replies = listOf(r0)))

        store.editReply("u1", r0, "edited", laterTs)

        val updated = store.get("u1")!!
        assertEquals("edited", updated.replies?.get(0)?.content)
        assertEquals(CommentStatus.PENDING, updated.replies?.get(0)?.status) // editing a Seen reply revives it
        assertEquals("edited", updated.content)
        assertEquals(updated, CommentJson.decode(Files.readString(dir.resolve("u1.json"))))
    }

    @Test
    fun `an op targets the reply by value, surviving an earlier reply's removal`(@TempDir dir: Path) {
        // The card renders indices, but a concurrent change shifts them; the store must locate the reply
        // by value. Edit "b" after "a" was removed: index-based would corrupt "c"; value-based hits "b".
        val store = store(dir)
        val a = sampleReply(content = "a")
        val b = sampleReply(content = "b")
        val c = sampleReply(content = "c")
        store.save(sampleComment(uuid = "u1", replies = listOf(a, b, c)))
        store.deleteReply("u1", a) // list is now [b, c]; b moved from index 1 → 0

        store.editReply("u1", b, "b-edited", laterTs) // pass the ORIGINAL b (as a stale card would)

        assertEquals(listOf("b-edited", "c"), store.get("u1")?.replies?.map { it.content })
    }

    @Test
    fun `rependReply revives a Seen reply and clears the tombstone`(@TempDir dir: Path) {
        val store = store(dir)
        val r0 = sampleReply(content = "please fix", status = CommentStatus.PROCESSED)
        store.save(sampleComment(uuid = "u1", replies = listOf(r0)))
        val tombstone = tombstone(dir, "u1")

        store.rependReply("u1", r0, laterTs)

        val updated = store.get("u1")!!
        assertEquals(CommentStatus.PENDING, updated.replies?.get(0)?.status)
        assertEquals("please fix", updated.replies?.get(0)?.content) // untouched by re-pend
        assertEquals("please fix", updated.content)
        assertFalse(Files.exists(tombstone)) // the "Seen" signal is gone so a reconcile can't re-mark it
        assertTrue(Files.exists(dir.resolve("u1.json")))
    }

    @Test
    fun `rependReply is a no-op on an already-pending reply`(@TempDir dir: Path) {
        val store = store(dir)
        val r0 = sampleReply(status = CommentStatus.PENDING)
        store.save(sampleComment(uuid = "u1", replies = listOf(r0)))
        var fires = 0
        store.addChangeListener { fires++ }

        store.rependReply("u1", r0, laterTs) // already actionable → nothing to revive

        assertEquals(0, fires)
    }

    @Test
    fun `addReply and editReply also clear the tombstone on the revive path`(@TempDir dir: Path) {
        val store = store(dir)
        val r0 = sampleReply(content = "seen", status = CommentStatus.PROCESSED)
        store.save(sampleComment(uuid = "u1", replies = listOf(r0)))
        val addTomb = tombstone(dir, "u1")
        store.addReply("u1", "reply", "me", laterTs)
        assertFalse(Files.exists(addTomb)) // addReply revived → tombstone cleared

        val editTomb = tombstone(dir, "u1")
        store.editReply("u1", r0, "edited", laterTs)
        assertFalse(Files.exists(editTomb)) // editReply revived → tombstone cleared
    }

    @Test
    fun `deleteReply removes one reply and keeps the others`(@TempDir dir: Path) {
        val store = store(dir)
        val a = sampleReply(content = "a")
        store.save(sampleComment(uuid = "u1", replies = listOf(a, sampleReply(content = "b"))))

        store.deleteReply("u1", a)

        val updated = store.get("u1")!!
        assertEquals(listOf("b"), updated.replies?.map { it.content })
        assertEquals("b", updated.content)
    }

    @Test
    fun `deleteReply preserves created_at when actionable replies remain`(@TempDir dir: Path) {
        val store = store(dir)
        val a = sampleReply(content = "a")
        // sampleComment.createdAt is fixed; deleting a reply must NOT bump it (reviewa parity — no reorder).
        store.save(sampleComment(uuid = "u1", replies = listOf(a, sampleReply(content = "b"))))
        val before = store.get("u1")!!.createdAt

        store.deleteReply("u1", a)

        assertEquals(before, store.get("u1")?.createdAt)
    }

    @Test
    fun `deleting the last reply drops the whole thread and unlinks the file`(@TempDir dir: Path) {
        val store = store(dir)
        val only = sampleReply(content = "only one")
        store.save(sampleComment(uuid = "u1", replies = listOf(only)))

        store.deleteReply("u1", only)

        assertNull(store.get("u1"))
        assertFalse(Files.exists(dir.resolve("u1.json")))
    }

    @Test
    fun `deleting the last pending reply keeps the Seen replies but unlinks the pool file`(@TempDir dir: Path) {
        val store = store(dir)
        val todo = sampleReply(content = "todo", status = CommentStatus.PENDING)
        store.save(
            sampleComment(
                uuid = "u1",
                replies = listOf(todo, sampleReply(content = "already seen", status = CommentStatus.PROCESSED)),
            ),
        )

        store.deleteReply("u1", todo) // remove the only actionable reply

        val updated = store.get("u1")!!
        assertEquals(CommentStatus.PROCESSED, updated.status)
        assertEquals(listOf("already seen"), updated.replies?.map { it.content }) // Seen reply retained in-UI
        assertFalse(Files.exists(dir.resolve("u1.json"))) // …but off the injectable pool (reviewa parity)
        assertFalse(store.isPersisted("u1")) // cleared before the unlink so the watcher won't evict it
    }

    @Test
    fun `markProcessed flips every reply to Seen and empties the derived content`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(
            sampleComment(uuid = "u1", replies = listOf(sampleReply(content = "a"), sampleReply(content = "b"))),
        )

        store.markProcessed("u1")

        val seen = store.get("u1")!!
        assertEquals(CommentStatus.PROCESSED, seen.status)
        assertEquals("", seen.content)
        assertTrue(seen.replies!!.all { it.status == CommentStatus.PROCESSED })
    }

    @Test
    fun `a reply added to a thread with a Seen reply injects only the pending one`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1", replies = listOf(sampleReply(content = "seen note", status = CommentStatus.PROCESSED))))

        store.addReply("u1", "new note", "marlzrana", laterTs)

        assertEquals("new note", store.get("u1")?.content) // the Seen reply is excluded from content
    }

    @Test
    fun `reply ops drop a blank input, unknown uuids, and replies no longer present without firing`(@TempDir dir: Path) {
        val store = store(dir)
        val keep = sampleReply(content = "keep me")
        store.save(sampleComment(uuid = "u1", replies = listOf(keep)))
        val absent = sampleReply(content = "not in the thread")
        var fires = 0
        store.addChangeListener { fires++ }

        store.addReply("u1", "   ", "me", laterTs)     // blank
        store.editReply("u1", keep, "\n\t ", laterTs)  // blank
        store.addReply("ghost", "hi", "me", laterTs)   // unknown uuid
        store.editReply("ghost", keep, "hi", laterTs)
        store.rependReply("ghost", keep, laterTs)
        store.deleteReply("ghost", keep)
        store.editReply("u1", absent, "x", laterTs)    // reply not present
        store.deleteReply("u1", absent)

        assertEquals(0, fires)
        assertEquals(listOf("keep me"), store.get("u1")?.replies?.map { it.content })
    }

    @Test
    fun `hydrate reconstructs a single reply for a legacy file with no replies array`(@TempDir dir: Path) {
        // sampleComment has replies=null → the encoded JSON has no "replies" key (a reviewa/legacy file).
        seed(dir, sampleComment(uuid = "leg", status = CommentStatus.PENDING))
        val store = store(dir, author = "legacy-author")

        store.hydrate()

        val loaded = store.get("leg")!!
        assertEquals(
            listOf(HeviewReply("make this a const", CommentStatus.PENDING, "legacy-author", loaded.createdAt)),
            loaded.replies,
        )
    }

    @Test
    fun `hydrate loads an explicit replies array and re-derives the hook-facing content from it`(@TempDir dir: Path) {
        // The top-level content ("make this a const") deliberately disagrees with the replies; hydrate must
        // re-derive it from the replies so a stale/tampered top-level can't be injected to the hooks.
        seed(
            dir,
            sampleComment(
                uuid = "r",
                status = CommentStatus.PENDING,
                replies = listOf(sampleReply(content = "a"), sampleReply(content = "b", status = CommentStatus.PROCESSED)),
            ),
        )
        val store = store(dir)

        store.hydrate()

        val loaded = store.get("r")!!
        assertEquals(listOf("a", "b"), loaded.replies?.map { it.content })
        assertEquals(listOf(CommentStatus.PENDING, CommentStatus.PROCESSED), loaded.replies?.map { it.status })
        assertEquals("a", loaded.content) // re-derived: only the PENDING reply, not the stale top-level
    }

    @Test
    fun `hydrate drops a malformed reply element and keeps the well-formed one`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        // A replies array mixing one valid reply with one missing `author` (raw JSON a foreign writer left).
        Files.writeString(
            dir.resolve("m.json"),
            """
            {"uuid":"m","status":"pending","created_at":"2026-08-15T00:00:00.000Z","workspace":"/repo",
             "abs_path":"/repo/src/Foo.kt","logical_abs_path":"/repo/src/Foo.kt","line_number":42,
             "line_content":"x","side":"file","content":"good",
             "replies":[{"content":"good","status":"pending","created_at":"2026-08-15T00:00:00.000Z"},
                        {"content":"good","status":"pending","author":"a","created_at":"2026-08-15T00:00:00.000Z"}]}
            """.trimIndent(),
        )
        val store = store(dir)

        store.hydrate()

        assertEquals(listOf("good"), store.get("m")?.replies?.map { it.content }) // only the well-formed reply
    }

    @Test
    fun `hydrate falls back to one reply from content when every replies element is malformed`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("bad.json"),
            """
            {"uuid":"bad","status":"pending","created_at":"2026-08-15T00:00:00.000Z","workspace":"/repo",
             "abs_path":"/repo/src/Foo.kt","logical_abs_path":"/repo/src/Foo.kt","line_number":42,
             "line_content":"x","side":"file","content":"reconstruct me",
             "replies":[{"status":"pending"}]}
            """.trimIndent(),
        )
        val store = store(dir, author = "fallback-author")

        store.hydrate()

        assertEquals(
            listOf(HeviewReply("reconstruct me", CommentStatus.PENDING, "fallback-author", "2026-08-15T00:00:00.000Z")),
            store.get("bad")?.replies,
        )
    }

    @Test
    fun `a null replies element skips only that file and does not abort the whole hydrate`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        seed(dir, sampleComment(uuid = "good")) // a valid neighbor that must still load
        Files.writeString(
            dir.resolve("nul.json"),
            """
            {"uuid":"nul","status":"pending","created_at":"2026-08-15T00:00:00.000Z","workspace":"/repo",
             "abs_path":"/repo/src/Foo.kt","logical_abs_path":"/repo/src/Foo.kt","line_number":42,
             "line_content":"x","side":"file","content":"ok",
             "replies":[null,{"content":"ok","status":"pending","author":"a","created_at":"2026-08-15T00:00:00.000Z"}]}
            """.trimIndent(),
        )
        val store = store(dir)

        store.hydrate()

        assertNotNull(store.get("good")) // the neighbor loaded — the null element didn't strand the pool
        assertEquals(listOf("ok"), store.get("nul")?.replies?.map { it.content }) // null element dropped
    }

    /** Write a consumption tombstone under comments/processed/ for [uuid] and return its path. */
    private fun tombstone(dir: Path, uuid: String): Path {
        val processed = Files.createDirectories(dir.resolve("processed"))
        val path = processed.resolve("$uuid.json")
        Files.writeString(path, CommentJson.encode(sampleComment(uuid = uuid, status = CommentStatus.PROCESSED)))
        return path
    }
}
