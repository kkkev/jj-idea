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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val PAGED_LOG_LOAD_PROPERTY = "jjidea.preview.pagedLogLoad"

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

    @BeforeEach
    fun enablePaging() {
        System.setProperty(PAGED_LOG_LOAD_PROPERTY, "true")
        JujutsuSettings.getInstance(projectFx.get()).state.logChangeLimit = pageSize
    }

    @AfterEach
    fun cleanup() {
        System.clearProperty(PAGED_LOG_LOAD_PROPERTY)
        JujutsuSettings.getInstance(projectFx.get()).state.logChangeLimit = 500
        drainBackgroundLoads(2_000)
    }

    private class FakeLogService(
        private val chain: List<LogEntry>,
        val getLogCalls: AtomicInteger,
        val limits: MutableList<Int?> = CopyOnWriteArrayList()
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
            val ids = Regex("""present\(([^)]+)\)""").findAll(revset.toString()).map { it.groupValues[1] }.toSet()
            val start = ids.mapNotNull { id -> chain.indexOfFirst { it.id.full == id }.takeIf { it >= 0 } }
                .minOrNull() ?: return Result.success(emptyList())
            return Result.success(chain.drop(start).take(limit ?: chain.size))
        }
    }

    private class Fake(val repo: JujutsuRepository, val calls: AtomicInteger, val limits: List<Int?>)

    private fun fakeRepo(length: Int): Fake {
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
        every { repo.logService } returns FakeLogService(chain, calls, limits)
        every { repo.logCache } returns mockk<LogCache>(relaxed = true)
        return Fake(repo, calls, limits)
    }

    private fun loaded(fake: Fake, rowCap: Int = TricklePolicy.ROW_CAP): UnifiedJujutsuLogDataLoader {
        val panel = mockk<CommitTablePanel<UnifiedJujutsuLogDataLoader.Data>>(relaxed = true)
        val loader = UnifiedJujutsuLogDataLoader(projectFx.get(), { listOf(fake.repo) }, panel)
        loader.trickleEnabled = false
        loader.trickleRowCap = rowCap
        loader.loadCommits()
        drainBackgroundLoads(1_000)
        return loader
    }

    @Test
    fun `each tick loads one page until the log is exhausted, then stops and costs nothing`() {
        val fake = fakeRepo(length = 35) // pages of 10: 10 on load, then 10, 10, 5
        val log = loaded(fake)
        val afterLoad = fake.calls.get()

        repeat(3) { (log.trickleTick() != null) shouldBe true }
        fake.calls.get() - afterLoad shouldBe 3

        log.trickleTick() shouldBe null // exhausted: nothing left to page
        fake.calls.get() - afterLoad shouldBe 3
    }

    @Test
    fun `stops at the row cap even though more history exists`() {
        val fake = fakeRepo(length = 400)
        val log = loaded(fake, rowCap = 25) // 10 loaded; ticks bring it to 20, then 30
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
    fun `paging off means the trickle does nothing`() {
        System.clearProperty(PAGED_LOG_LOAD_PROPERTY)
        val fake = fakeRepo(length = 100)
        val log = loaded(fake)
        val afterLoad = fake.calls.get()
        log.trickleTick() shouldBe null
        fake.calls.get() shouldBe afterLoad
    }

    @Test
    fun `a huge page size gets a small first fetch, then full-size pages`() {
        JujutsuSettings.getInstance(projectFx.get()).state.logChangeLimit = 1_000
        val fake = fakeRepo(length = 3_000)
        val log = loaded(fake)
        log.trickleTick()
        log.trickleTick()

        fake.limits shouldBe listOf(PagedLogWindow.FIRST_PAGE_ROWS, 1_000, 1_000)
    }

    @Test
    fun `a page size below the first-page size is used as is`() {
        val fake = fakeRepo(length = 100) // setUp's page size is 10
        loaded(fake)
        fake.limits shouldBe listOf(pageSize)
    }
}
