package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogCache
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * jj-idea-ehwm.3: [UnifiedJujutsuLogDataLoader.applyFilter] has jj resolve the log filters, paged, so
 * matches deep in history reach the table. Same fake-repo harness as [PagedLogRevsetChangeTest]: a
 * linear chain `a-0` (newest) .. `a-29`, with the filter matching every fifth commit (an
 * `author_email(...)` term; a `every7()` scope matches every seventh) and a revset containing `bad(` failing. Pages are 3 rows, so the base
 * window only ever holds `a-0..a-2`.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class RevsetLogFilterLoaderTest {
    private val projectFx = projectFixture()

    @AfterEach
    fun cleanup() {
        drainBackgroundLoads(500)
    }

    private inner class FakeLogService(private val chain: List<LogEntry>) : LogService by mockk(relaxed = true) {
        val headsCalls = AtomicInteger()
        val pageCalls = CopyOnWriteArrayList<Pair<String, Int?>>()

        private fun predicate(revset: String): (Int) -> Boolean = when {
            "author_email(" in revset -> { i -> i % 5 == 0 }
            "every7()" in revset -> { i -> i % 7 == 0 }
            else -> { _ -> true }
        }

        private fun index(id: String) = id.removePrefix("commit-").removePrefix("a-").toInt()

        /** The chain indexes [revset] selects, newest first: FilterPageCursor's `(R) & ::(F)` minus `~ (shown)`. */
        private fun evaluate(revset: String): List<Int> {
            val parts = revset.split(" ~ (", limit = 2)
            fun ids(text: String) = Regex("""present\(([^)]+)\)""").findAll(text).map {
                index(it.groupValues[1])
            }.toList()
            val start = ids(parts[0]).minOrNull() ?: 0
            val excluded = parts.getOrNull(1)?.let(::ids)?.toSet() ?: emptySet()
            return chain.indices.filter { it >= start && it !in excluded }.filter(predicate(revset))
        }

        override fun getLogHeads(revset: Revset): Result<List<ChangeId>> {
            if ("bad(" in revset.toString()) return Result.failure(RuntimeException("Error from jj log: bad revset"))
            if ("present(\"t\")" in revset.toString()) {
                return Result.failure(RuntimeException("Error: Change ID prefix `t` is ambiguous"))
            }
            headsCalls.incrementAndGet()
            return Result.success(evaluate(revset.toString()).take(1).map { chain[it].id })
        }

        override fun getLog(
            revset: Revset,
            filePaths: List<FilePath>,
            limit: Int?,
            quiet: Boolean
        ): Result<List<LogEntry>> {
            pageCalls.add(revset.toString() to limit)
            // A base-log page is `(base) & ((present(..)) | ancestors(present(..)))`, closed under ancestry.
            val base = "ancestors(" in revset.toString()
            val rows = if (base) {
                val start = Regex("""present\(([^)]+)\)""").findAll(revset.toString())
                    .map { index(it.groupValues[1]) }.minOrNull() ?: return Result.success(emptyList())
                chain.drop(start)
            } else {
                evaluate(revset.toString()).map { chain[it] }
            }
            return Result.success(rows.take(limit ?: rows.size))
        }
    }

    private lateinit var service: FakeLogService
    private val published = CopyOnWriteArrayList<UnifiedJujutsuLogDataLoader.Data>()

    private fun loader(length: Int = 30): UnifiedJujutsuLogDataLoader {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        every { repo.displayName } returns "a"
        every { repo.directory } returns mockk<VirtualFile>(relaxed = true) { every { path } returns "/fake/a" }
        val chain = (0 until length).map { i ->
            LogEntry(
                repo = repo,
                id = ChangeId(full = "a-$i", short = "a-$i"),
                commitId = CommitId("commit-a-$i"),
                underlyingDescription = "desc $i",
                parentIds = if (i ==
                    length - 1
                ) {
                    emptyList()
                } else {
                    listOf(ChangeId(full = "a-${i + 1}", short = "a-${i + 1}"))
                }
            )
        }
        service = FakeLogService(chain)
        every { repo.logService } returns service
        // A stateful cache: the loader's full merge reads logCache.all, which a bare relaxed mock leaves empty.
        val cache = mutableListOf<LogEntry>()
        every { repo.logCache } returns mockk<LogCache>(relaxed = true) {
            every { store(any()) } answers { synchronized(cache) { cache.addAll(firstArg<List<LogEntry>>()) } }
            every { clear() } answers { synchronized(cache) { cache.clear() } }
            every { all } answers { synchronized(cache) { cache.distinctBy { it.key } } }
            every { reload() } answers {
                synchronized(cache) {
                    cache.clear()
                    cache.addAll(chain)
                }
                chain
            }
        }
        val panel = mockk<CommitTablePanel<UnifiedJujutsuLogDataLoader.Data>>(relaxed = true)
        every { panel.onDataLoaded(any()) } answers { published.add(firstArg()) }
        return UnifiedJujutsuLogDataLoader(projectFx.get(), { listOf(repo) }, panel).also {
            it.trickleEnabled = false
            it.pageRows = 3
            it.maxPageRows = 3
            repoRef = repo
        }
    }

    private lateinit var repoRef: JujutsuRepository

    private fun keysOf(vararg indexes: Int) = indexes.mapTo(HashSet()) {
        ChangeKey(repoRef, ChangeId("a-$it", "a-$it"))
    }

    private val aliceFilter = LogFilterState(authorEmails = setOf("alice@example.com"))

    @Test
    fun `a match deep in history reaches the table though it is outside the loaded window`() {
        val loader = loader()
        loader.loadCommits()
        drainBackgroundLoads(500)
        published.last().entries.map { it.id.short } shouldContainExactly listOf("a-0", "a-1", "a-2")
        published.last().filterMatches.shouldBeNull()

        loader.applyFilter(aliceFilter)
        drainBackgroundLoads(500)

        val data = published.last()
        data.filterMatches shouldBe keysOf(0, 5, 10)
        // a-5 and a-10 were never in the base window: they were fetched by the filter's own pages.
        data.entries.map { it.id.short }.containsAll(listOf("a-5", "a-10")) shouldBe true
    }

    @Test
    fun `applying a filter costs a seed, one page and one remainder query per repo`() {
        val loader = loader()
        loader.loadCommits()
        drainBackgroundLoads(500)
        val headsBefore = service.headsCalls.get()
        val pagesBefore = service.pageCalls.size

        loader.applyFilter(aliceFilter)
        drainBackgroundLoads(500)

        service.headsCalls.get() - headsBefore shouldBe 2
        service.pageCalls.size - pagesBefore shouldBe 1
        service.pageCalls.last().second shouldBe 3
        service.pageCalls.last().first shouldContain "author_email(exact:\"alice@example.com\")"
    }

    @Test
    fun `loadMore pages the filter's matches, not the base log, while a filter is active`() {
        val loader = loader()
        loader.loadCommits()
        drainBackgroundLoads(500)
        loader.applyFilter(aliceFilter)
        drainBackgroundLoads(500)
        val pagesBefore = service.pageCalls.size

        loader.loadMore()
        drainBackgroundLoads(500)

        service.pageCalls.size - pagesBefore shouldBe 1
        service.pageCalls.last().first shouldContain "author_email("
        // Page 2 continues after the first page's last match - nothing repeated, nothing skipped.
        published.last().filterMatches shouldBe keysOf(0, 5, 10, 15, 20, 25)
        published.last().entries.map { it.id.short }.contains("a-3") shouldBe false
    }

    @Test
    fun `a short last page needs no remainder query and ends the walk`() {
        val loader = loader()
        loader.loadCommits()
        drainBackgroundLoads(500)
        loader.applyFilter(LogFilterState(scope = "every7()")) // matches 0, 7, 14, 21, 28: pages of 3 then 2
        drainBackgroundLoads(500)
        val headsBefore = service.headsCalls.get()

        loader.loadMore()
        drainBackgroundLoads(500)

        service.headsCalls.get() shouldBe headsBefore // the short page proves it was the last
        published.last().filterMatches shouldBe keysOf(0, 7, 14, 21, 28)
        val pagesBefore = service.pageCalls.size

        loader.loadMore()
        drainBackgroundLoads(500)

        service.pageCalls.size shouldBe pagesBefore
    }

    @Test
    fun `refresh re-walks the filter for as many pages as were loaded`() {
        val loader = loader()
        loader.loadCommits()
        drainBackgroundLoads(500)
        loader.applyFilter(aliceFilter)
        drainBackgroundLoads(500)
        loader.loadMore() // two filter pages now
        drainBackgroundLoads(500)
        val filterPagesBefore = service.pageCalls.count { "author_email(" in it.first }

        loader.refresh()
        drainBackgroundLoads(500)

        service.pageCalls.count { "author_email(" in it.first } - filterPagesBefore shouldBe 2
        published.last().filterMatches shouldBe keysOf(0, 5, 10, 15, 20, 25)
    }

    @Test
    fun `an ambiguous id prefix in the search falls back to a text-only search`() {
        val loader = loader()
        loader.loadCommits()
        drainBackgroundLoads(500)
        var errors: Map<JujutsuRepository, String>? = null

        loader.applyFilter(LogFilterState(searchText = "t")) { errors = it }
        drainBackgroundLoads(500)

        errors.orEmpty().isEmpty() shouldBe true
        published.last().filterMatches shouldBe keysOf(0, 1, 2)
        service.pageCalls.last().first.contains("present(\"t\")") shouldBe false
        service.pageCalls.last().first shouldContain "description(substring-i:\"t\")"
    }

    @Test
    fun `a failing revset-mode search is reported, keeps the previous rows and is not retried as text`() {
        val loader = loader()
        loader.loadCommits()
        drainBackgroundLoads(500)
        loader.applyFilter(aliceFilter)
        drainBackgroundLoads(500)
        val before = published.last().filterMatches
        var errors: Map<JujutsuRepository, String>? = null

        loader.applyFilter(LogFilterState(searchText = "bad(", revsetMode = true)) { errors = it }
        drainBackgroundLoads(500)

        errors.orEmpty().values.single() shouldContain "bad revset"
        published.last().filterMatches shouldBe before
    }

    @Test
    fun `an ambiguous id prefix in revset mode is the user's error, not retried as text`() {
        val loader = loader()
        loader.loadCommits()
        drainBackgroundLoads(500)
        var errors: Map<JujutsuRepository, String>? = null

        loader.applyFilter(LogFilterState(searchText = "present(\"t\")", revsetMode = true)) { errors = it }
        drainBackgroundLoads(500)

        errors.orEmpty().values.single() shouldContain "ambiguous"
    }

    @Test
    fun `clearing the filter drops its rows and matches`() {
        val loader = loader()
        loader.loadCommits()
        drainBackgroundLoads(500)
        loader.applyFilter(aliceFilter)
        drainBackgroundLoads(500)

        loader.applyFilter(LogFilterState())
        drainBackgroundLoads(500)

        published.last().filterMatches.shouldBeNull()
    }

    @Test
    fun `a filter that matches nothing publishes an empty match set, not null`() {
        // An empty chain, so nothing satisfies the filter.
        val loader = loader(length = 0)

        loader.applyFilter(aliceFilter)
        drainBackgroundLoads(500)

        published.last().filterMatches shouldBe emptySet()
    }

    @Test
    fun `a failing filter is reported with jj's message and keeps the previous rows`() {
        val loader = loader()
        loader.loadCommits()
        drainBackgroundLoads(500)
        loader.applyFilter(aliceFilter)
        drainBackgroundLoads(500)
        var errors: Map<JujutsuRepository, String>? = null

        loader.applyFilter(LogFilterState(scope = "bad(")) { errors = it }
        drainBackgroundLoads(500)

        errors!!.let {
            it.shouldContainKey(repoRef)
            it.getValue(repoRef) shouldBe "Error from jj log: bad revset"
        }
        published.last().filterMatches shouldBe keysOf(0, 5, 10)
    }

    @Test
    fun `a superseded apply does not report`() {
        val loader = loader()
        loader.loadCommits()
        drainBackgroundLoads(500)
        var firstReported = false

        loader.applyFilter(aliceFilter) { firstReported = true }
        loader.applyFilter(LogFilterState()) { }
        drainBackgroundLoads(500)

        firstReported shouldBe false
        published.last().filterMatches.shouldBeNull()
    }
}
