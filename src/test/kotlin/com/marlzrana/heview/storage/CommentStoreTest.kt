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
    fun `binFromPool of a genuine orphan unlinks the live pool file and drops the record`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1"))
        var fires = 0
        store.addChangeListener { fires++ }
        store.binFromPool("u1")
        assertEquals(1, fires) // drops the record + fires (matches evict/delete/markProcessed)
        assertNull(store.get("u1"))
        assertFalse(Files.exists(dir.resolve("u1.json")))
    }

    @Test
    fun `binFromPool keeps the tombstone of a comment a hook already consumed`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1"))
        val tomb = tombstone(dir, "u1")              // the hook's atomic move: comments/u1.json -> processed/u1.json …
        Files.deleteIfExists(dir.resolve("u1.json")) // … which removed the live file

        store.binFromPool("u1") // a reload raced the consume and saw a still-PENDING, drifted thread

        assertNull(store.get("u1"))    // dropped from this client's index (the narrow race residual)
        assertTrue(Files.exists(tomb)) // but the tombstone SURVIVES → peers + a restart still resolve it to Seen
    }

    @Test
    fun `binFromPool is a no-op for an unknown uuid`(@TempDir dir: Path) {
        val store = store(dir)
        var fires = 0
        store.addChangeListener { fires++ }
        store.binFromPool("ghost")
        assertEquals(0, fires)
        assertNull(store.get("ghost"))
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
        val appended = updated.replies!!.last() // its id is freshly generated, so assert the fields
        assertEquals("second", appended.content)
        assertEquals(CommentStatus.PENDING, appended.status)
        assertEquals("marlzrana", appended.author)
        assertEquals(laterTs, appended.createdAt)
        assertTrue(appended.id.isNotBlank())
        assertEquals(updated, CommentJson.decode(Files.readString(dir.resolve("u1.json")))) // written back
        assertTrue(store.isPersisted("u1"))
    }

    @Test
    fun `editReply replaces a reply's text and revives it, bumping both timestamps`(@TempDir dir: Path) {
        val store = store(dir)
        val r0 = sampleReply(content = "old", status = CommentStatus.PROCESSED)
        store.save(sampleComment(uuid = "u1", replies = listOf(r0)))

        store.editReply("u1", r0, "edited", laterTs)

        val updated = store.get("u1")!!
        assertEquals("edited", updated.replies?.get(0)?.content)
        assertEquals(CommentStatus.PENDING, updated.replies?.get(0)?.status) // editing a Seen reply revives it
        assertEquals("edited", updated.content)
        assertEquals(laterTs, updated.createdAt) // top-level bumped (drives injection ordering)
        assertEquals(laterTs, updated.replies?.get(0)?.createdAt) // the edited reply bumped
        val onDisk = CommentJson.decode(Files.readString(dir.resolve("u1.json")))
        assertEquals(updated, onDisk)
        assertEquals(laterTs, onDisk.replies?.get(0)?.createdAt) // and it survives the round-trip
    }

    @Test
    fun `an op targets the reply by its stable id, surviving an earlier reply's removal`(@TempDir dir: Path) {
        // The card renders indices and may hold a stale copy; the store must locate the reply by its id.
        // Edit "b" after "a" was removed: an index/value match would misfire; the id hits "b".
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
        assertEquals(laterTs, updated.createdAt) // top-level bumped
        assertEquals(laterTs, updated.replies?.get(0)?.createdAt) // the re-pended reply bumped
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
            // id derived from the thread uuid (stable across reconstructions).
            listOf(HeviewReply("make this a const", CommentStatus.PENDING, "legacy-author", loaded.createdAt, id = "leg")),
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
            listOf(HeviewReply("reconstruct me", CommentStatus.PENDING, "fallback-author", "2026-08-15T00:00:00.000Z", id = "bad")),
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

    @Test
    fun `hydrate skips a file whose replies are all Seen even when the top-level says pending`(@TempDir dir: Path) {
        // Inconsistent top-level (pending) vs replies (all processed) → re-derived to PROCESSED → skipped.
        seed(
            dir,
            sampleComment(
                uuid = "s",
                status = CommentStatus.PENDING,
                replies = listOf(
                    sampleReply(content = "a", status = CommentStatus.PROCESSED),
                    sampleReply(content = "b", status = CommentStatus.PROCESSED),
                ),
            ),
        )
        val store = store(dir)

        store.hydrate()

        assertNull(store.get("s")) // a fully-Seen thread is not actionable — not hydrated
    }

    @Test
    fun `hydrate loads a processed file that still carries a pending reply, as pending`(@TempDir dir: Path) {
        // A top-level `processed` file whose replies still include a PENDING one is re-derived to pending
        // (the early skip is gated on an empty replies array).
        seed(
            dir,
            sampleComment(uuid = "p", status = CommentStatus.PROCESSED, replies = listOf(sampleReply(content = "still todo"))),
        )
        val store = store(dir)

        store.hydrate()

        val loaded = store.get("p")!!
        assertEquals(CommentStatus.PENDING, loaded.status)
        assertEquals("still todo", loaded.content)
    }

    @Test
    fun `reviving refuses to delete a tombstone through a symlinked processed dir`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        val outside = Files.createDirectories(dir.resolve("outside"))
        Files.writeString(outside.resolve("u1.json"), "keep me")
        try {
            Files.createSymbolicLink(dir.resolve("processed"), outside)
        } catch (e: Exception) {
            assumeTrue(false, "symbolic links not supported on this platform")
        }
        val store = store(dir)
        val r0 = sampleReply(content = "x", status = CommentStatus.PROCESSED)
        store.save(sampleComment(uuid = "u1", replies = listOf(r0)))

        store.rependReply("u1", r0, laterTs) // revive → would clear the tombstone, but processed/ is a symlink

        // The outside file must be untouched — clearTombstone refuses to follow the symlink.
        assertTrue(Files.exists(outside.resolve("u1.json")))
        assertEquals("keep me", Files.readString(outside.resolve("u1.json")))
    }

    @Test
    fun `a failed revive keeps the tombstone and creates no pool file`(@TempDir dir: Path) {
        val store = store(dir)
        val r0 = sampleReply(content = "x", status = CommentStatus.PROCESSED)
        store.save(sampleComment(uuid = "u1", replies = listOf(r0)))
        val tomb = tombstone(dir, "u1")
        Files.deleteIfExists(dir.resolve("u1.json")) // a genuinely consumed thread: only the tombstone remains
        makeReadOnlyOrSkip(dir) // the revive's write will now fail
        try {
            store.rependReply("u1", r0, laterTs)
            assertTrue(Files.exists(tomb)) // write failed → the only durable copy is retained
            assertFalse(Files.exists(dir.resolve("u1.json"))) // and no half-revived pool file was created
        } finally {
            dir.toFile().setWritable(true)
        }
    }

    @Test
    fun `hydrate backfills distinct ids for id-less replies and routes edits by id`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("f.json"),
            """
            {"uuid":"f","status":"pending","created_at":"2026-08-15T00:00:00.000Z","workspace":"/repo",
             "abs_path":"/repo/src/Foo.kt","logical_abs_path":"/repo/src/Foo.kt","line_number":42,
             "line_content":"x","side":"file","content":"a\n\nb",
             "replies":[{"content":"a","status":"pending","author":"x","created_at":"2026-08-15T00:00:00.000Z"},
                        {"content":"b","status":"pending","author":"x","created_at":"2026-08-15T00:00:00.000Z"}]}
            """.trimIndent(),
        )
        val store = store(dir)
        store.hydrate()

        val replies = store.get("f")!!.replies!!
        assertEquals(2, replies.size)
        assertTrue(replies[0].id.isNotBlank() && replies[1].id.isNotBlank())
        assertTrue(replies[0].id != replies[1].id) // distinct backfilled ids — not both null/index-0

        // Edit the SECOND row (as the card would, holding replies[1]); only it changes.
        store.editReply("f", replies[1], "b-edited", laterTs)
        assertEquals(listOf("a", "b-edited"), store.get("f")?.replies?.map { it.content })
    }

    @Test
    fun `deleting the final reply of a consumed thread also removes its tombstone`(@TempDir dir: Path) {
        val store = store(dir)
        val only = sampleReply(content = "x")
        store.save(sampleComment(uuid = "u1", replies = listOf(only)))
        val tomb = tombstone(dir, "u1") // the thread had been consumed at some point

        store.deleteReply("u1", only) // last reply → whole thread deleted

        assertNull(store.get("u1"))
        assertFalse(Files.exists(dir.resolve("u1.json"))) // pool file gone
        assertFalse(Files.exists(tomb)) // …and the restorable tombstone too (explicit delete = gone)
    }

    @Test
    fun `hydrate assigns distinct ids to duplicate foreign reply ids and routes edits correctly`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        // Two replies deliberately sharing an id "dup" (a malformed foreign file).
        Files.writeString(
            dir.resolve("d.json"),
            """
            {"uuid":"d","status":"pending","created_at":"2026-08-15T00:00:00.000Z","workspace":"/repo",
             "abs_path":"/repo/src/Foo.kt","logical_abs_path":"/repo/src/Foo.kt","line_number":42,
             "line_content":"x","side":"file","content":"a\n\nb",
             "replies":[{"content":"a","status":"pending","author":"x","created_at":"2026-08-15T00:00:00.000Z","id":"dup"},
                        {"content":"b","status":"pending","author":"x","created_at":"2026-08-15T00:00:00.000Z","id":"dup"}]}
            """.trimIndent(),
        )
        val store = store(dir)
        store.hydrate()

        val replies = store.get("d")!!.replies!!
        assertTrue(replies[0].id != replies[1].id) // the duplicate id was backfilled to a distinct one

        store.editReply("d", replies[1], "b-edited", laterTs) // edit the second row
        assertEquals(listOf("a", "b-edited"), store.get("d")?.replies?.map { it.content }) // only it changed
    }

    @Test
    fun `re-pending a genuinely consumed thread recreates the pool file then deletes the tombstone`(@TempDir dir: Path) {
        val store = store(dir)
        val r0 = sampleReply(content = "please fix")
        store.save(sampleComment(uuid = "u1", replies = listOf(r0))) // writes comments/u1.json (pending)
        // Simulate a hook consuming it: move the pool file into processed/, mark the thread Seen in memory.
        val processed = Files.createDirectories(dir.resolve("processed"))
        Files.move(dir.resolve("u1.json"), processed.resolve("u1.json"))
        store.markProcessed("u1")
        assertFalse(Files.exists(dir.resolve("u1.json"))) // consumed: only the tombstone is on disk
        assertTrue(Files.exists(processed.resolve("u1.json")))

        store.rependReply("u1", store.get("u1")!!.replies!![0], laterTs)

        val revived = store.get("u1")!!
        assertEquals(CommentStatus.PENDING, revived.status)
        assertTrue(Files.exists(dir.resolve("u1.json"))) // pool file recreated…
        assertEquals(
            CommentStatus.PENDING,
            CommentJson.decode(Files.readString(dir.resolve("u1.json"))).replies?.get(0)?.status,
        ) // …with the pending reply persisted
        assertFalse(Files.exists(processed.resolve("u1.json"))) // tombstone deleted only after the write
    }

    @Test
    fun `updateLocation rewrites line_number and line_content in memory and on disk`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1")) // line_number 42, line_content "val x = 1"

        store.updateLocation("u1", 7, "val y = 2")

        assertEquals(7, store.get("u1")?.lineNumber)
        assertEquals("val y = 2", store.get("u1")?.lineContent)
        val onDisk = CommentJson.decode(Files.readString(dir.resolve("u1.json")))
        assertEquals(7, onDisk.lineNumber)
        assertEquals("val y = 2", onDisk.lineContent)
    }

    @Test
    fun `updateLocation is a no-op when the location is unchanged`(@TempDir dir: Path) {
        var writes = 0
        val store = CommentStore(dir, runIo = { writes++; it.run() }, runEdt = { it.run() }, defaultAuthor = { "tester" })
        store.save(sampleComment(uuid = "u1")) // 1 write
        assertEquals(1, writes)

        store.updateLocation("u1", 42, "val x = 1") // identical to the sample → no write
        assertEquals(1, writes)

        store.updateLocation("u1", 43, "moved") // a real change → one more write
        assertEquals(2, writes)
    }

    @Test
    fun `updateLocation never fires the change listener`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1"))
        var fired = 0
        store.addChangeListener { fired++ } // registered AFTER the save
        store.updateLocation("u1", 99, "moved")
        assertEquals(0, fired) // the card already tracks the live marker; no reconcile needed
    }

    @Test
    fun `updateLocation ignores an unknown uuid`(@TempDir dir: Path) {
        val store = store(dir)
        store.updateLocation("nope", 5, "x")
        assertNull(store.get("nope"))
        assertFalse(Files.exists(dir.resolve("nope.json")))
    }

    @Test
    fun `updateLocation updates memory but never recreates a consumed thread's pool file`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1"))
        // Simulate a hook consuming the thread: its file leaves comments/ and it is no longer persisted.
        Files.delete(dir.resolve("u1.json"))
        store.markProcessed("u1")

        store.updateLocation("u1", 7, "moved")

        assertFalse(Files.exists(dir.resolve("u1.json"))) // must NOT recreate an injectable duplicate
        assertEquals(7, store.get("u1")?.lineNumber) // in-memory record IS updated (a later re-pend uses it)
        assertEquals("moved", store.get("u1")?.lineContent)
    }

    @Test
    fun `updateLocation does not recreate a pool file consumed after its write was queued`(@TempDir dir: Path) {
        val tasks = ArrayDeque<Runnable>()
        val store = CommentStore(dir, runIo = { tasks.add(it) }, runEdt = { it.run() }, defaultAuthor = { "tester" })
        store.save(sampleComment(uuid = "u1"))
        tasks.removeFirst().run() // complete the create-save → file exists, persisted

        store.updateLocation("u1", 7, "moved") // optimistic memory update + queues the write

        // A hook consumes the thread AFTER the write was queued but BEFORE the IO task runs.
        Files.delete(dir.resolve("u1.json"))
        store.markProcessed("u1")

        tasks.removeFirst().run() // the queued write must re-check isPersisted/exists and skip
        assertFalse(Files.exists(dir.resolve("u1.json"))) // not resurrected
    }

    @Test
    fun `updateLocation does not recreate a pool file that left comments before markProcessed ran`(@TempDir dir: Path) {
        val tasks = ArrayDeque<Runnable>()
        val store = CommentStore(dir, runIo = { tasks.add(it) }, runEdt = { it.run() }, defaultAuthor = { "tester" })
        store.save(sampleComment(uuid = "u1"))
        tasks.removeFirst().run() // create → file exists, persisted

        store.updateLocation("u1", 7, "moved") // queues the write

        // A hook atomically moved the file out of comments/, but the watcher hasn't run markProcessed yet, so
        // isPersisted is STILL true. Only the Files.exists half of the IO recheck can prevent a resurrection.
        Files.delete(dir.resolve("u1.json"))

        tasks.removeFirst().run()
        assertTrue(store.isPersisted("u1")) // watcher lag: still believed persisted…
        assertFalse(Files.exists(dir.resolve("u1.json"))) // …so the Files.exists check is what stopped recreation
    }

    @Test
    fun `updateLocation failure rollback does not clobber a newer queued update`(@TempDir dir: Path) {
        val tasks = ArrayDeque<Runnable>()
        val store = CommentStore(dir, runIo = { tasks.add(it) }, runEdt = { it.run() }, defaultAuthor = { "tester" })
        store.save(sampleComment(uuid = "u1"))
        tasks.removeFirst().run() // create → persisted, file exists

        store.updateLocation("u1", 7, "seven") // task A (older); memory → 7
        store.updateLocation("u1", 9, "nine") // task B (newer); memory → 9

        makeReadOnlyOrSkip(dir)
        tasks.removeFirst().run() // task A: write fails; its identity-guarded revert must NOT fire (index is B)
        dir.toFile().setWritable(true)
        assertEquals(9, store.get("u1")?.lineNumber) // newer in-memory update preserved

        tasks.removeFirst().run() // task B: writes line 9
        assertEquals(9, store.get("u1")?.lineNumber)
        assertEquals(9, CommentJson.decode(Files.readString(dir.resolve("u1.json"))).lineNumber)
    }

    @Test
    fun `updateLocation queued during an in-flight create still writes the moved line`(@TempDir dir: Path) {
        val tasks = ArrayDeque<Runnable>()
        val store = CommentStore(dir, runIo = { tasks.add(it) }, runEdt = { it.run() }, defaultAuthor = { "tester" })
        store.save(sampleComment(uuid = "u1")) // queues the create write (line 42); NOT persisted yet

        store.updateLocation("u1", 7, "moved") // called while the create is in flight → must still queue a write

        tasks.removeFirst().run() // create write: line 42, persisted
        tasks.removeFirst().run() // update write: now persisted + file exists → line 7 (not dropped)
        assertEquals(7, store.get("u1")?.lineNumber)
        assertEquals(7, CommentJson.decode(Files.readString(dir.resolve("u1.json"))).lineNumber)
    }

    @Test
    fun `updateLocation persists a content-only change on the same line`(@TempDir dir: Path) {
        var writes = 0
        val store = CommentStore(dir, runIo = { writes++; it.run() }, runEdt = { it.run() }, defaultAuthor = { "tester" })
        store.save(sampleComment(uuid = "u1")) // line 42, "val x = 1"; 1 write
        store.updateLocation("u1", 42, "val x = 2") // SAME line, edited text → must still write (guard is OR)
        assertEquals(2, writes)
        assertEquals("val x = 2", store.get("u1")?.lineContent)
        assertEquals(42, store.get("u1")?.lineNumber)
        assertEquals("val x = 2", CommentJson.decode(Files.readString(dir.resolve("u1.json"))).lineContent)
    }

    @Test
    fun `updateLocation retries on the next save after a failed write`(@TempDir dir: Path) {
        // Fail exactly one write by toggling the temp dir read-only for that write.
        val store = CommentStore(dir, runIo = { it.run() }, runEdt = { it.run() }, defaultAuthor = { "tester" })
        store.save(sampleComment(uuid = "u1"))

        makeReadOnlyOrSkip(dir)
        store.updateLocation("u1", 7, "moved") // write fails; optimistic memory update is reverted
        dir.toFile().setWritable(true)
        assertEquals(42, store.get("u1")?.lineNumber) // reverted, so a same-marker save still differs & retries

        store.updateLocation("u1", 7, "moved") // the retry now succeeds
        assertEquals(7, store.get("u1")?.lineNumber)
        assertEquals(7, CommentJson.decode(Files.readString(dir.resolve("u1.json"))).lineNumber)
    }

    @Test
    fun `updateLocation persists the whole comment on disk, not just the two fields`(@TempDir dir: Path) {
        val store = store(dir)
        val replies = listOf(sampleReply(content = "a"), sampleReply(content = "b", status = CommentStatus.PROCESSED))
        val before = sampleComment(uuid = "u1", replies = replies)
        store.save(before)

        store.updateLocation("u1", 7, "moved")

        // The on-disk file must round-trip the full schema (replies/status/created_at/paths/side) with only
        // the two anchor fields changed — a stripped/partial encode would corrupt the contract the hooks read.
        val onDisk = CommentJson.decode(Files.readString(dir.resolve("u1.json")))
        assertEquals(before.copy(lineNumber = 7, lineContent = "moved"), onDisk)
    }

    @Test
    fun `updateLocation leaves replies and status untouched`(@TempDir dir: Path) {
        val store = store(dir)
        val replies = listOf(sampleReply(content = "a"), sampleReply(content = "b", status = CommentStatus.PROCESSED))
        store.save(sampleComment(uuid = "u1", replies = replies))
        val before = store.get("u1")!!

        store.updateLocation("u1", 7, "moved")

        val after = store.get("u1")!!
        assertEquals(before.replies, after.replies)
        assertEquals(before.status, after.status)
        assertEquals(before.content, after.content)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(7, after.lineNumber) // only the anchor moved
    }

    // ---- generation fence (optimistic-concurrency CAS on the shared pool; plan.html §5) ----

    @Test
    fun `create starts at generation 0 and each reply mutation bumps it`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1", replies = listOf(sampleReply(content = "a"))))
        assertEquals(0, store.get("u1")?.generation)
        assertEquals(0, CommentJson.decode(Files.readString(dir.resolve("u1.json"))).generation)

        store.addReply("u1", "b", "me", laterTs)
        assertEquals(1, store.get("u1")?.generation) // in memory…
        assertEquals(1, CommentJson.decode(Files.readString(dir.resolve("u1.json"))).generation) // …and on disk

        val second = store.get("u1")!!.replies!![1]
        store.editReply("u1", second, "b-edited", laterTs)
        assertEquals(2, store.get("u1")?.generation)
        assertEquals(2, CommentJson.decode(Files.readString(dir.resolve("u1.json"))).generation)
    }

    @Test
    fun `hydrate loads the on-disk generation`(@TempDir dir: Path) {
        seed(dir, sampleComment(uuid = "u1").copy(generation = 5))
        val store = store(dir)
        store.hydrate()
        assertEquals(5, store.get("u1")?.generation)
    }

    @Test
    fun `a legacy file without a generation loads at 0 and its first mutation writes generation 1`(@TempDir dir: Path) {
        // A genuinely pre-fence / foreign file has no `generation` key → Gson decodes it as 0.
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("u1.json"),
            """{"uuid":"u1","status":"pending","created_at":"2026-08-15T00:00:00.000Z","workspace":"/repo",
               "abs_path":"/repo/src/Foo.kt","logical_abs_path":"/repo/src/Foo.kt","line_number":42,
               "line_content":"x","side":"file","content":"hi"}""".trimIndent(),
        )
        val store = store(dir)
        store.hydrate()
        assertEquals(0, store.get("u1")?.generation) // absent field → 0

        store.addReply("u1", "reply", "me", laterTs)
        assertEquals(1, CommentJson.decode(Files.readString(dir.resolve("u1.json"))).generation)
    }

    @Test
    fun `addReply merges onto a peer's concurrent reply instead of clobbering it`(@TempDir dir: Path) {
        val tasks = ArrayDeque<Runnable>()
        val store = CommentStore(dir, runIo = { tasks.add(it) }, runEdt = { it.run() }, defaultAuthor = { "tester" })
        val a = sampleReply(content = "a")
        store.save(sampleComment(uuid = "u1", replies = listOf(a)))
        tasks.removeFirst().run() // create → disk gen 0, [a]

        store.addReply("u1", "mine", "me", laterTs) // optimistic [a, mine] gen 1; queues the commit

        // A peer appends its own reply to the on-disk file (bumping the generation) BEFORE our commit runs.
        val peer = sampleReply(content = "peer", author = "peer")
        Files.writeString(
            dir.resolve("u1.json"),
            CommentJson.encode(sampleComment(uuid = "u1", replies = listOf(a, peer)).copy(generation = 1)),
        )

        tasks.removeFirst().run() // commit reads [a, peer] gen 1, re-applies "mine" on top, writes gen 2

        val onDisk = CommentJson.decode(Files.readString(dir.resolve("u1.json")))
        assertEquals(listOf("a", "peer", "mine"), onDisk.replies?.map { it.content }) // peer's reply preserved
        assertEquals(2, onDisk.generation) // base gen 1 + 1
        assertEquals(listOf("a", "peer", "mine"), store.get("u1")?.replies?.map { it.content }) // memory adopted the merge
    }

    @Test
    fun `editing a reply a peer already deleted reconciles to the peer's thread`(@TempDir dir: Path) {
        val tasks = ArrayDeque<Runnable>()
        val store = CommentStore(dir, runIo = { tasks.add(it) }, runEdt = { it.run() }, defaultAuthor = { "tester" })
        val a = sampleReply(content = "a")
        val b = sampleReply(content = "b")
        store.save(sampleComment(uuid = "u1", replies = listOf(a, b)))
        tasks.removeFirst().run() // disk gen 0, [a, b]

        store.editReply("u1", b, "b-edited", laterTs) // optimistic edits b; queues the commit

        // A peer deletes reply b on disk (leaving [a]) and bumps the generation.
        Files.writeString(
            dir.resolve("u1.json"),
            CommentJson.encode(sampleComment(uuid = "u1", replies = listOf(a)).copy(generation = 1)),
        )

        tasks.removeFirst().run() // commit: b is gone on disk → adopt the peer's thread, drop our edit

        assertEquals(listOf("a"), store.get("u1")?.replies?.map { it.content })
        assertEquals(1, store.get("u1")?.generation) // in-memory now tracks the peer's generation
        assertEquals(listOf("a"), CommentJson.decode(Files.readString(dir.resolve("u1.json"))).replies?.map { it.content })
    }

    @Test
    fun `updateLocation skips a thread a peer changed instead of clobbering it`(@TempDir dir: Path) {
        val store = store(dir)
        store.save(sampleComment(uuid = "u1")) // disk gen 0
        // A peer rewrites the thread on disk (new reply, bumped generation) after our create.
        val peerThread = sampleComment(uuid = "u1", replies = listOf(sampleReply(content = "peer note"))).copy(generation = 1)
        Files.writeString(dir.resolve("u1.json"), CommentJson.encode(peerThread))

        store.updateLocation("u1", 7, "moved") // our base gen is 0 but disk is gen 1 → must skip, not clobber

        assertEquals(peerThread, CommentJson.decode(Files.readString(dir.resolve("u1.json")))) // untouched
        assertEquals(7, store.get("u1")?.lineNumber) // the line still moved in memory (a race doesn't revert it)
    }

    @Test
    fun `re-pending a consumed thread resurrects it at the next generation`(@TempDir dir: Path) {
        val store = store(dir)
        val r0 = sampleReply(content = "fix", status = CommentStatus.PROCESSED)
        // The thread was last written at generation 3, so its in-memory record carries gen 3.
        store.save(sampleComment(uuid = "u1", replies = listOf(r0)).copy(generation = 3))
        Files.deleteIfExists(dir.resolve("u1.json")) // consumed: the pool file is gone
        store.markProcessed("u1")

        store.rependReply("u1", r0, laterTs)

        assertTrue(Files.exists(dir.resolve("u1.json"))) // resurrected…
        assertEquals(4, CommentJson.decode(Files.readString(dir.resolve("u1.json"))).generation) // …at base 3 + 1
    }

    /** Write a consumption tombstone under comments/processed/ for [uuid] and return its path. */
    private fun tombstone(dir: Path, uuid: String): Path {
        val processed = Files.createDirectories(dir.resolve("processed"))
        val path = processed.resolve("$uuid.json")
        Files.writeString(path, CommentJson.encode(sampleComment(uuid = uuid, status = CommentStatus.PROCESSED)))
        return path
    }
}
