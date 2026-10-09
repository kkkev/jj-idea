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
import java.util.concurrent.CopyOnWriteArraySet

private const val PAGED_LOG_LOAD_PROPERTY = "jjidea.preview.pagedLogLoad"

/**
 * Pins the page-1-only contract of [UnifiedJujutsuLogDataLoader.refresh] against a content change
 * on an already-loaded *deeper* page (jj-idea-hp6c; gap found by jj-idea-cf2c's S4 spike).
 * `PagedLogWindowContractTest` covers paging correctness (no missing/duplicate commits) and
 * `PagedLogLoaderConcurrencyTest`/`PagedLogRevsetChangeTest` cover locking and config staleness,
 * but nothing asserted what the cheap post-write [UnifiedJujutsuLogDataLoader.refresh] does when
 * a row on page 2+ changes state (e.g. a conflict resolved there): it deliberately does *not*
 * restate it (jj-idea-2c8k, GitHub #69 - O(page size), not O(scrolled depth)), while
 * [UnifiedJujutsuLogDataLoader.forceRefresh] re-walks every loaded page and does.
 *
 * Why this matters: jj-idea-cmc3 (resolve a conflict at any revision) will resolve rows that may
 * sit on page 2+, and must call `forceRefresh()` (or patch the row in place) afterwards or the row
 * keeps showing `hasConflict = true`. If someone "fixes" `refresh()` to restate deep pages, the
 * first test below fails and points them at GitHub #69's latency regression; if someone breaks
 * `forceRefresh()`, the second does.
 *
 * Same fake-repo harness as [PagedLogRevsetChangeTest] (see its doc), extended with a mutable set
 * of "conflicted" change ids that [FakeLogService] applies to whatever it serves.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class PagedLogDeepPageRefreshTest {
    private val projectFx = projectFixture()

    @BeforeEach
    fun enablePaging() {
        System.setProperty(PAGED_LOG_LOAD_PROPERTY, "true")
    }

    @AfterEach
    fun cleanup() {
        System.clearProperty(PAGED_LOG_LOAD_PROPERTY)
        // Longer than the sibling paged-log tests' drain: these also run loadMore() and
        // forceRefresh(), leaving more pooled-thread work in flight when the project is torn down.
        drainBackgroundLoads(5_000)
    }

    private class FakeLogService(private val chain: List<LogEntry>) : LogService by mockk(relaxed = true) {
        /** Full change ids whose entries are currently served with `hasConflict = true`. */
        val conflicted = CopyOnWriteArraySet<String>()

        /** Full change ids currently served with a rewritten commit id (describe/edit: same change, same parents). */
        val rewritten = CopyOnWriteArraySet<String>()

        override fun getLogHeads(revset: Revset): Result<List<ChangeId>> = Result.success(listOf(chain.first().id))

        override fun getLog(
            revset: Revset,
            filePaths: List<FilePath>,
            limit: Int?,
            quiet: Boolean
        ): Result<List<LogEntry>> {
            val ids = Regex("""present\(([^)]+)\)""").findAll(revset.toString()).map { it.groupValues[1] }.toSet()
            val startIndex = ids.mapNotNull { id ->
                chain.indexOfFirst { it.id.full == id }.takeIf { it >= 0 }
            }.minOrNull() ?: return Result.success(emptyList())
            val page = chain.drop(startIndex).take(limit ?: chain.size)
            return Result.success(
                page.map {
                    it.copy(
                        hasConflict = it.id.full in conflicted,
                        commitId = if (it.id.full in rewritten) CommitId("rewritten-${it.id.full}") else it.commitId
                    )
                }
            )
        }
    }

    /** [stored] records every list handed to [LogCache.store], in order - the last is the current view. */
    private class FakeRepo(
        val repo: JujutsuRepository,
        val logService: FakeLogService,
        val stored: List<List<LogEntry>>
    )

    private fun fakeChainRepo(name: String, length: Int): FakeRepo {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        every { repo.displayName } returns name
        every { repo.directory } returns mockk<VirtualFile>(relaxed = true) { every { path } returns "/fake/$name" }

        val chain = (0 until length).map { i ->
            LogEntry(
                repo = repo,
                id = ChangeId(full = "$name-$i", short = "$name-$i"),
                commitId = CommitId("commit-$name-$i"),
                underlyingDescription = "desc $i",
                parentIds = if (i == length - 1) {
                    emptyList()
                } else {
                    listOf(ChangeId(full = "$name-${i + 1}", short = "$name-${i + 1}"))
                }
            )
        }

        val logService = FakeLogService(chain)
        every { repo.logService } returns logService
        val stored = CopyOnWriteArrayList<List<LogEntry>>()
        every { repo.logCache } returns mockk<LogCache>(relaxed = true) {
            every { store(any()) } answers { stored.add(firstArg<List<LogEntry>>()) }
            // The full merge re-reads the cache, so it must serve what was last stored.
            every { all } answers { stored.lastOrNull() ?: emptyList() }
        }
        return FakeRepo(repo, logService, stored)
    }

    /** Every [UnifiedJujutsuLogDataLoader.Data] the loader has handed to its panel, in order. */
    private val applied = CopyOnWriteArrayList<UnifiedJujutsuLogDataLoader.Data>()

    private fun loader(repo: JujutsuRepository): UnifiedJujutsuLogDataLoader {
        val panel = mockk<CommitTablePanel<UnifiedJujutsuLogDataLoader.Data>>(relaxed = true)
        every { panel.onDataLoaded(any()) } answers { applied.add(firstArg()) }
        return UnifiedJujutsuLogDataLoader(projectFx.get(), { listOf(repo) }, panel)
            // jj-idea-2570.5: this test counts/orders fetches itself - no background trickle
            .also {
                it.trickleEnabled = false
                it.pageRows = 10 // short fake chains must span several pages
            }
    }

    private fun FakeRepo.hasConflictInCurrentView(changeId: String): Boolean =
        stored.last().single { it.id.full == changeId }.hasConflict

    /** Loads page 1 and page 2 (entries 0-19 of 30), then marks a page-2 row as newly resolved-elsewhere. */
    private fun loadedThroughPage2WithDeepConflictCleared(): Pair<FakeRepo, UnifiedJujutsuLogDataLoader> {
        val fake = fakeChainRepo("a", length = 30)
        fake.logService.conflicted += DEEP_ROW // conflicted when first loaded
        val log = loader(fake.repo)
        log.loadCommits()
        drainBackgroundLoads(1_000)
        log.loadMore()
        drainBackgroundLoads(1_000)
        fake.hasConflictInCurrentView(DEEP_ROW) shouldBe true

        fake.logService.conflicted -= DEEP_ROW // the write: conflict resolved on a page-2 row
        return fake to log
    }

    @Test
    fun `refresh leaves a changed row on an already-loaded deeper page stale`() {
        val (fake, log) = loadedThroughPage2WithDeepConflictCleared()

        log.refresh()
        drainBackgroundLoads(1_000)

        // Pins the deliberate page-1-only splice (jj-idea-2c8k / GitHub #69): the deep row keeps its
        // previously-loaded state, and the view keeps its depth.
        fake.stored.last().size shouldBe 20
        fake.hasConflictInCurrentView(DEEP_ROW) shouldBe true
    }

    @Test
    fun `forceRefresh picks up a changed row on an already-loaded deeper page`() {
        val (fake, log) = loadedThroughPage2WithDeepConflictCleared()

        log.forceRefresh()
        drainBackgroundLoads(1_000)

        fake.stored.last().size shouldBe 20 // scroll depth preserved
        fake.hasConflictInCurrentView(DEEP_ROW) shouldBe false
    }

    // jj-idea-43qg / jj-idea-2570.13: a post-write refresh must not relayout when nothing about the
    // topology changed. Operation count: layouts per refresh = 0 for no-op and content-only changes.

    @Test
    fun `refresh with nothing changed applies nothing and lays nothing out`() {
        val fake = fakeChainRepo("a", length = 30)
        val log = loader(fake.repo)
        log.loadCommits()
        drainBackgroundLoads(1_000)
        log.loadMore()
        drainBackgroundLoads(1_000)
        val appliedBefore = applied.size

        log.refresh()
        drainBackgroundLoads(1_000)

        applied.size shouldBe appliedBefore
    }

    @Test
    fun `refresh after a content-only change reuses the graph nodes instead of relaying out`() {
        val fake = fakeChainRepo("a", length = 30)
        val log = loader(fake.repo)
        log.loadCommits()
        drainBackgroundLoads(1_000)
        log.loadMore()
        drainBackgroundLoads(1_000)
        val before = applied.last()
        val appliedBefore = applied.size

        fake.logService.rewritten += "a-2"
        log.refresh()
        drainBackgroundLoads(1_000)

        applied.size shouldBe appliedBefore + 1
        val after = applied.last()
        after.entries.single { it.id.full == "a-2" }.commitId shouldBe CommitId("rewritten-a-2")
        (after.graphNodes === before.graphNodes) shouldBe true
    }

    private companion object {
        /** Index 15 of a 30-entry chain paged at 10: firmly inside page 2. */
        const val DEEP_ROW = "a-15"
    }
}
