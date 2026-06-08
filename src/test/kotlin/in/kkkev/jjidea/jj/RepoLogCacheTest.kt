package `in`.kkkev.jjidea.jj

import com.intellij.openapi.project.Project
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
    private lateinit var cache: RepoLogCache

    @BeforeEach
    fun setup() {
        logService = mockk()
        // project must be a relaxed mock so that the RepoLogCache init block's
        // messageBus.connect().subscribe() calls succeed without a real platform.
        val project = mockk<Project>(relaxed = true)
        repo = mockk {
            every { this@mockk.project } returns project
            every { this@mockk.logService } returns this@RepoLogCacheTest.logService
            every { displayName } returns "test-repo"
        }
        cache = RepoLogCache(repo)
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
    fun `get by change id returns null when logService returns empty`() {
        val id = ChangeId("missing", "missing", null)
        every { logService.getLog(revset = id) } returns Result.success(emptyList())

        cache[id] shouldBe null
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

        // byBookmark should no longer point at entryA; fallback hits logService
        every { logService.getLog(revset = bm.name) } returns Result.success(emptyList())
        cache[bm.name] shouldBe null
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

            // After clear both fall through to logService; stub them to return empty
            every { logService.getLog(revset = mutableE.id) } returns Result.success(emptyList())
            every { logService.getLog(revset = immutableE.id) } returns Result.success(emptyList())

            cache[mutableE.id] shouldBe null
            cache[immutableE.id] shouldBe null
        }

        @Test
        fun `get by bookmark returns null after clear (no stale index)`() {
            val bm = bookmark("main")
            val e = entry("aaa", immutable = true, bookmarks = listOf(bm))
            cache.store(listOf(e))

            cache.clear()

            every { logService.getLog(revset = bm.name) } returns Result.success(emptyList())
            cache[bm.name] shouldBe null
        }

        @Test
        fun `get by commit id returns null after clear (no stale index)`() {
            val e = entry("aaa", immutable = true)
            cache.store(listOf(e))

            cache.clear()

            every { logService.getLog(revset = e.commitId) } returns Result.success(emptyList())
            cache[e.commitId] shouldBe null
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
}
