package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogCache
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * jj-idea-2570.5 operation-count tests for the paged log's idle trickle: each
 * [UnifiedJujutsuLogDataLoader.trickleTick] costs exactly one `getLog` (one page) until the log
 * is fully loaded or the row cap is reached, then costs nothing. The background alarm is disabled
 * ([UnifiedJujutsuLogDataLoader.trickleEnabled]) so the test drives ticks itself, deterministically.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class PagedLogTrickleTest {
    private val projectFx = projectFixture()
    private val pageSize = 10

    @AfterEach
    fun cleanup() {
        drainBackgroundLoads(2_000)
    }

    private class FakeLogService(
        private val chain: List<LogEntry>,
        val getLogCalls: AtomicInteger,
        val limits: MutableList<Int?> = CopyOnWriteArrayList(),
        val clock: AtomicLong = AtomicLong(),
        val nanosPerRow: Long = 0
    ) :
        LogService by mockk(relaxed = true) {
        override fun getLogHeads(revset: Revset): Result<List<ChangeId>> = Result.success(listOf(chain.first().id))

        override fun getLog(
            revset: Revset,
            filePaths: List<FilePath>,
            limit: Int?,
            quiet: Boolean
        ): Result<List<LogEntry>> {
            getLogCalls.incrementAndGet()
            limits += limit
            clock.addAndGet((limit ?: chain.size) * nanosPerRow) // a fake fetch takes this long
            val ids = Regex("""present\(([^)]+)\)""").findAll(revset.toString()).map { it.groupValues[1] }.toSet()
            val start = ids.mapNotNull { id -> chain.indexOfFirst { it.id.full == id }.takeIf { it >= 0 } }
                .minOrNull() ?: return Result.success(emptyList())
            return Result.success(chain.drop(start).take(limit ?: chain.size))
        }
    }

    private class Fake(
        val repo: JujutsuRepository,
        val calls: AtomicInteger,
        val limits: List<Int?>,
        val clock: AtomicLong
    )

    private fun fakeRepo(length: Int, msPerRow: Long = 0): Fake {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        every { repo.displayName } returns "a"
        every { repo.directory } returns mockk<VirtualFile>(relaxed = true) { every { path } returns "/fake/a" }
        val chain = (0 until length).map { i ->
            LogEntry(
                repo = repo,
                id = ChangeId("a-$i", "a-$i"),
                commitId = CommitId("commit-a-$i"),
                underlyingDescription = "desc $i",
                parentIds = if (i == length - 1) emptyList() else listOf(ChangeId("a-${i + 1}", "a-${i + 1}"))
            )
        }
        val calls = AtomicInteger(0)
        val limits = CopyOnWriteArrayList<Int?>()
        val clock = AtomicLong()
        every { repo.logService } returns FakeLogService(chain, calls, limits, clock, msPerRow * 1_000_000)
        every { repo.logCache } returns mockk<LogCache>(relaxed = true)
        return Fake(repo, calls, limits, clock)
    }

    private fun loaded(
        fake: Fake,
        rowCap: Int = TricklePolicy.ROW_CAP,
        rows: Int = pageSize,
        maxRows: Int = PageSizePolicy.MAX_PAGE_ROWS
    ): UnifiedJujutsuLogDataLoader {
        val panel = mockk<CommitTablePanel<UnifiedJujutsuLogDataLoader.Data>>(relaxed = true)
        val loader = UnifiedJujutsuLogDataLoader(projectFx.get(), { listOf(fake.repo) }, panel)
        loader.trickleEnabled = false
        loader.trickleRowCap = rowCap
        loader.pageRows = rows
        loader.maxPageRows = maxRows
        loader.clockNanos = fake.clock::get
        loader.loadCommits()
        drainBackgroundLoads(1_000)
        return loader
    }

    @Test
    fun `each tick loads one page until the log is exhausted, then stops and costs nothing`() {
        val fake = fakeRepo(length = 35) // pages of 10: 10 on load, then 10, 10, 5
        val log = loaded(fake, maxRows = pageSize) // fixed-size pages
        val afterLoad = fake.calls.get()

        repeat(3) { (log.trickleTick() != null) shouldBe true }
        fake.calls.get() - afterLoad shouldBe 3

        log.trickleTick() shouldBe null // exhausted: nothing left to page
        fake.calls.get() - afterLoad shouldBe 3
    }

    @Test
    fun `stops at the row cap even though more history exists`() {
        val fake = fakeRepo(length = 400)
        val log = loaded(fake, rowCap = 25, maxRows = pageSize) // 10 loaded; ticks bring it to 20, then 30
        val afterLoad = fake.calls.get()

        (log.trickleTick() != null) shouldBe true // 20 rows
        (log.trickleTick() != null) shouldBe true // 30 rows >= cap
        log.trickleTick() shouldBe null
        fake.calls.get() - afterLoad shouldBe 2
    }

    @Test
    fun `the delay after a tick follows the throttle policy`() {
        val fake = fakeRepo(length = 100)
        val log = loaded(fake)
        val delay = log.trickleTick()
        (delay!! in TricklePolicy.MIN_DELAY_MS..TricklePolicy.MAX_DELAY_MS) shouldBe true
    }

    @Test
    fun `the page size is the constant PAGE_ROWS whatever Changes to show says`() {
        val settings = JujutsuSettings.getInstance(projectFx.get())
        settings.state.logChangeLimit = 10_000
        try {
            val fake = fakeRepo(length = 1_700, msPerRow = 1) // 500 rows take 500 ms, under the 700 ms budget
            val log = loaded(fake, rows = PagedLogWindow.PAGE_ROWS) // the production default
            log.trickleTick()
            log.trickleTick()

            // The first paint is 500 rows, not the 10,000 setting; later pages are sized to the time budget
            // (jj-idea-2570.9): 500 rows in 500 ms scales to 700 rows, which again take 700 ms.
            fake.limits shouldBe listOf(PagedLogWindow.PAGE_ROWS, 700, 700)
        } finally {
            settings.state.logChangeLimit = 500
        }
    }

    @Test
    fun `fast fetches grow pages by at most double per page, up to the ceiling`() {
        val fake = fakeRepo(length = 200) // instant fetches
        val log = loaded(fake, maxRows = 40)
        repeat(4) { log.trickleTick() }

        fake.limits shouldBe listOf(10, 20, 40, 40, 40)
        fake.calls.get() shouldBe 5 // 150 rows in 5 fetches; fixed 10-row pages would need 15
    }

    @Test
    fun `slow fetches keep pages at the first-page size`() {
        val fake = fakeRepo(length = 200, msPerRow = 100) // 10 rows take 1 s, over the budget
        val log = loaded(fake, maxRows = 40)
        repeat(3) { log.trickleTick() }

        fake.limits shouldBe List(4) { 10 }
    }
}
