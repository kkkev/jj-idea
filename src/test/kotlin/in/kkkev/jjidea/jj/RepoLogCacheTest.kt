package `in`.kkkev.jjidea.jj

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.settings.JujutsuSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RepoLogCacheTest {
    private lateinit var repo: JujutsuRepository
    private lateinit var logService: LogService
    private lateinit var project: Project
    private lateinit var cache: RepoLogCache

    @BeforeEach
    fun setup() {
        logService = mockk()
        // project must be a relaxed mock so that the RepoLogCache init block's
        // messageBus.connect().subscribe() calls succeed without a real platform.
        project = mockk<Project>(relaxed = true)
        repo = mockk {
            every { this@mockk.project } returns this@RepoLogCacheTest.project
            every { this@mockk.logService } returns this@RepoLogCacheTest.logService
            every { displayName } returns "test-repo"
        }
        cache = RepoLogCache(repo)
    }

    /** Stubs [JujutsuSettings.getInstance] for tests that exercise the [LogCache.all] path. */
    private fun stubSettings(logRevset: String = "", logChangeLimit: Int = 100): JujutsuSettings =
        mockk<JujutsuSettings>(relaxed = true).also { settings ->
            every { settings.logRevset(repo) } returns logRevset
            every { settings.logChangeLimit(repo) } returns logChangeLimit
            every { project.getService(JujutsuSettings::class.java) } returns settings
        }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun entry(
        id: String,
        commitId: String = "commit-$id",
        bookmarks: List<Bookmark> = emptyList(),
        immutable: Boolean = false
    ) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId(commitId),
        underlyingDescription = "desc $id",
        bookmarks = bookmarks,
        immutable = immutable
    )

    private fun bookmark(name: String) = Bookmark(name, tracked = true)

    // ─── store / get ─────────────────────────────────────────────────────────

    @Test
    fun `get by change id returns stored entry`() {
        val e = entry("aaa")
        cache.store(listOf(e))

        cache[e.id] shouldBe e
    }

    @Test
    fun `get by commit id returns stored entry`() {
        val e = entry("aaa")
        cache.store(listOf(e))

        cache[e.commitId] shouldBe e
    }

    @Test
    fun `get by bookmark name returns stored entry`() {
        val bm = bookmark("main")
        val e = entry("aaa", bookmarks = listOf(bm))
        cache.store(listOf(e))

        cache[bm.name] shouldBe e
    }

    @Test
    fun `get by change id falls back to logService on miss and stores result`() {
        val e = entry("zzz")
        every { logService.getLog(revset = e.id) } returns Result.success(listOf(e))

        val result = cache[e.id]

        result shouldBe e
        // second call should be served from cache — no extra logService calls
        cache[e.id] shouldBe e
        verify(exactly = 1) { logService.getLog(revset = e.id) }
    }

    @Test
    fun `get by change id throws when logService returns empty`() {
        val id = ChangeId("missing", "missing", null)
        every { logService.getLog(revset = id) } returns Result.success(emptyList())

        shouldThrow<IllegalArgumentException> { cache[id] }
    }

    @Test
    fun `all throws when logService returns failure`() {
        val cause = RuntimeException("jj not found")
        stubSettings()
        // cache is empty → snapshot() is null → all calls fetch(Revset.Default, limit=100)
        every { logService.getLog(revset = Revset.Default, limit = 100) } returns Result.failure(cause)

        val thrown = shouldThrow<RuntimeException> { cache.all }
        thrown.message shouldBe "jj not found"
    }

    @Test
    fun `get throws original exception when logService returns failure`() {
        val id = ChangeId("aaa", "aaa", null)
        val cause = RuntimeException("jj not found")
        every { logService.getLog(revset = id) } returns Result.failure(cause)

        val thrown = shouldThrow<RuntimeException> { cache[id] }
        thrown.message shouldBe "jj not found"
    }

    // ─── bookmark index correctness ───────────────────────────────────────────

    @Test
    fun `bookmark index updated when bookmark moves to another entry`() {
        val bm = bookmark("main")
        val entryA = entry("aaa", bookmarks = listOf(bm))
        val entryB = entry("bbb", bookmarks = listOf(bm))

        cache.store(listOf(entryA))
        cache[bm.name] shouldBe entryA

        // bookmark moves: re-store a fresh batch where main is on B, A has no bookmarks
        cache.store(listOf(entry("aaa"), entryB))
        cache[bm.name] shouldBe entryB
    }

    @Test
    fun `bookmark index cleared when bookmark is removed from immutable entry`() {
        val bm = bookmark("trunk")
        val entryA = entry("aaa", immutable = true, bookmarks = listOf(bm))
        cache.store(listOf(entryA))

        // re-store the same change id without the bookmark (e.g. after jj bookmark delete + refresh)
        cache.store(listOf(entry("aaa", immutable = true)))

        // byBookmark should no longer point at entryA; fallback hits logService, returns empty → throws
        every { logService.getLog(revset = bm.name) } returns Result.success(emptyList())
        shouldThrow<IllegalArgumentException> { cache[bm.name] }
    }

    // ─── clear ───────────────────────────────────────────────────────────────

    @Nested
    inner class `clear` {
        @Test
        fun `evicts mutable and immutable entries alike`() {
            val mutableE = entry("mut", immutable = false)
            val immutableE = entry("imm", immutable = true)
            cache.store(listOf(mutableE, immutableE))

            cache.clear()

            // After clear both fall through to logService; stub them to return empty → throws
            every { logService.getLog(revset = mutableE.id) } returns Result.success(emptyList())
            every { logService.getLog(revset = immutableE.id) } returns Result.success(emptyList())

            shouldThrow<IllegalArgumentException> { cache[mutableE.id] }
            shouldThrow<IllegalArgumentException> { cache[immutableE.id] }
        }

        @Test
        fun `get by bookmark throws after clear (no stale index)`() {
            val bm = bookmark("main")
            val e = entry("aaa", immutable = true, bookmarks = listOf(bm))
            cache.store(listOf(e))

            cache.clear()

            every { logService.getLog(revset = bm.name) } returns Result.success(emptyList())
            shouldThrow<IllegalArgumentException> { cache[bm.name] }
        }

        @Test
        fun `get by commit id throws after clear (no stale index)`() {
            val e = entry("aaa", immutable = true)
            cache.store(listOf(e))

            cache.clear()

            every { logService.getLog(revset = e.commitId) } returns Result.success(emptyList())
            shouldThrow<IllegalArgumentException> { cache[e.commitId] }
        }

        @Test
        fun `after clear, re-stored entry is returned correctly`() {
            val e = entry("aaa")
            cache.store(listOf(e))
            cache.clear()

            val fresh = entry("aaa", commitId = "commit-aaa-fresh")
            cache.store(listOf(fresh))

            cache[e.id]?.commitId shouldBe fresh.commitId
        }
    }

    // ─── ordering / deduplication ─────────────────────────────────────────────

    @Test
    fun `store deduplicates orderedIds when same entry is re-stored`() {
        val e = entry("aaa")
        cache.store(listOf(e))
        val updated = entry("aaa", commitId = "commit-aaa-v2")
        cache.store(listOf(updated))

        // Should return the latest version
        cache[e.id] shouldNotBe null
        cache[e.id]?.commitId shouldBe updated.commitId
    }

    // ─── reload ───────────────────────────────────────────────────────────────

    @Test
    fun `reload evicts existing entries and re-fetches from logService`() {
        val old = entry("aaa")
        cache.store(listOf(old))

        val fresh = entry("bbb")
        stubSettings()
        // cache is empty after clear → all calls fetch(Revset.Default, limit=100) via stubbed settings
        every { logService.getLog(revset = Revset.Default, limit = 100) } returns Result.success(listOf(fresh))

        val result = cache.reload()

        // Returns the freshly fetched entries and has stored them
        result shouldBe listOf(fresh)
        cache[fresh.id] shouldBe fresh
        // Old entry was evicted (clear was called before re-fetch)
        cache.all.none { it.id == old.id } shouldBe true
        verify(exactly = 1) { logService.getLog(revset = Revset.Default, limit = 100, filePaths = emptyList()) }
    }
}
