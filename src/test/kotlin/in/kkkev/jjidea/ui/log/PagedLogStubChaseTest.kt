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
import `in`.kkkev.jjidea.ui.log.graph.ParentState
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * jj-idea-ytvh operation-count tests for the stub chase: a wanted parent that is `k` pages deep costs
 * exactly `k` `getLog` calls (one per [UnifiedJujutsuLogDataLoader.chaseBaseStubs]), and a parent that
 * is not paged-for, or the row cap, costs none.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class PagedLogStubChaseTest {
    private val projectFx = projectFixture()
    private val pageSize = 10

    private companion object {
        const val DEEP = 35
    }

    @AfterEach
    fun cleanup() {
        drainBackgroundLoads(2_000)
    }

    private class FakeLogService(private val chain: List<LogEntry>, val getLogCalls: AtomicInteger) :
        LogService by mockk(relaxed = true) {
        override fun getLogHeads(revset: Revset, quiet: Boolean): Result<List<ChangeId>> = Result.success(
            listOf(chain.first().id)
        )

        override fun getLog(
            revset: Revset,
            filePaths: List<FilePath>,
            limit: Int?,
            quiet: Boolean
        ): Result<List<LogEntry>> {
            getLogCalls.incrementAndGet()
            val ids = Regex("""present\(([^)]+)\)""").findAll(revset.toString()).map { it.groupValues[1] }.toSet()
            val start = ids.mapNotNull { id -> chain.indexOfFirst { it.id.full == id }.takeIf { it >= 0 } }
                .minOrNull() ?: return Result.success(emptyList())
            return Result.success(chain.drop(start).take(limit ?: chain.size))
        }
    }

    private class Fake(val repo: JujutsuRepository, val calls: AtomicInteger, val chain: List<LogEntry>)

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
                parentIds = when {
                    i == length - 1 -> emptyList()
                    // The head also parents a deep row, so that row is a frontier member from page 1 on.
                    i == 0 -> listOf(ChangeId("a-1", "a-1"), ChangeId("a-$DEEP", "a-$DEEP"))
                    else -> listOf(ChangeId("a-${i + 1}", "a-${i + 1}"))
                }
            )
        }
        val calls = AtomicInteger(0)
        every { repo.logService } returns FakeLogService(chain, calls)
        every { repo.logCache } returns mockk<LogCache>(relaxed = true)
        return Fake(repo, calls, chain)
    }

    private fun loaded(fake: Fake, rowCap: Int = TricklePolicy.ROW_CAP): UnifiedJujutsuLogDataLoader {
        val panel = mockk<CommitTablePanel<UnifiedJujutsuLogDataLoader.Data>>(relaxed = true)
        val loader = UnifiedJujutsuLogDataLoader(projectFx.get(), { listOf(fake.repo) }, panel)
        loader.trickleEnabled = false
        loader.pageRows = pageSize
        loader.maxPageRows = pageSize // fixed-size pages
        loader.chaseRowCap = rowCap
        loader.loadCommits()
        drainBackgroundLoads(1_000)
        return loader
    }

    private fun want(fake: Fake, index: Int) =
        mapOf(fake.repo to setOf(fake.chain[index].id))

    @Test
    fun `a parent k pages deep costs exactly k getLog calls, then nothing`() {
        val fake = fakeRepo(length = 100)
        val log = loaded(fake) // rows 0..9; row 10's parent chain is the frontier
        val afterLoad = fake.calls.get()
        val wanted = want(fake, DEEP) // on the fourth page after the first

        // Mirrors the panel: re-ask after each page; it stops costing once the parent has loaded.
        repeat(10) { log.chaseBaseStubs(wanted) }
        fake.calls.get() - afterLoad shouldBe 3 // rows 10-19, 20-29, 30-39

        log.chaseBaseStubs(wanted) // loaded now: no longer pending
        fake.calls.get() - afterLoad shouldBe 3
    }

    @Test
    fun `a parent that is not a frontier member is never chased`() {
        val fake = fakeRepo(length = 100)
        val log = loaded(fake)
        val afterLoad = fake.calls.get()

        log.chaseBaseStubs(want(fake, 5)) // already loaded
        log.chaseBaseStubs(mapOf(fake.repo to setOf(ChangeId("elsewhere", "elsewhere"))))
        fake.calls.get() - afterLoad shouldBe 0
    }

    @Test
    fun `the row cap stops the chase`() {
        val fake = fakeRepo(length = 100)
        val log = loaded(fake, rowCap = 10) // already at the cap after the first page
        val afterLoad = fake.calls.get()

        log.chaseBaseStubs(want(fake, DEEP))
        fake.calls.get() - afterLoad shouldBe 0
    }

    @Test
    fun `only NOT_LOADED stubs of the given rows are collected`() {
        val fake = fakeRepo(length = 5)
        val key = { i: Int -> fake.chain[i].key }
        val missing = ChangeKey(fake.repo, ChangeId("far", "far"))
        val hidden = ChangeKey(fake.repo, ChangeId("hid", "hid"))
        val nodes = mapOf(
            key(0) to GraphNode(0, unresolvedParents = mapOf(missing to ParentState.NOT_LOADED)),
            key(1) to GraphNode(0, unresolvedParents = mapOf(hidden to ParentState.HIDDEN)),
            key(2) to
                GraphNode(
                    0,
                    unresolvedParents = mapOf(
                        ChangeKey(fake.repo, ChangeId("off", "off")) to ParentState.NOT_LOADED
                    )
                )
        )

        stubParentsToLoad(0..1, { fake.chain.getOrNull(it) }, nodes) shouldBe setOf(missing)
    }
}
