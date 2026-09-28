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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

private const val PAGED_LOG_LOAD_PROPERTY = "jjidea.preview.pagedLogLoad"

/**
 * Regression coverage for jj-idea-vqpn: [UnifiedJujutsuLogDataLoader.refresh]'s cheap per-write
 * path reused an already-seeded [PagedLogWindow] unconditionally, but a [PagedLogWindow]'s
 * [PagedLogWindow.baseRevset]/[PagedLogWindow.pageSize] are fixed at construction and never
 * change on their own — so once paging was engaged, changing a repo's Log Revset (or Log Limit)
 * in Settings had no visible effect until an explicit Refresh (which always builds a fresh
 * window), even though [in.kkkev.jjidea.settings.JujutsuConfigurable.apply]'s
 * [in.kkkev.jjidea.jj.JujutsuStateModel.logRefresh] notification correctly triggered [refresh].
 * Found via manual testing of jj-idea-vqpn's revset filter chip, which ANDs against this same
 * per-repo revset.
 *
 * Same fake-repo/[FakeLogService] harness as `PagedLogLoaderConcurrencyTest` (see that class's
 * doc for why a real delegate-to-relaxed-mockk implementation is used instead of stubbing
 * [Revset]-typed mockk `every {}` matchers, which can't handle a sealed interface backed by an
 * inline value class).
 */
@Tag("platform")
@TestApplication
@RunInEdt
class PagedLogRevsetChangeTest {
    private val projectFx = projectFixture()

    @BeforeEach
    fun enablePaging() {
        System.setProperty(PAGED_LOG_LOAD_PROPERTY, "true")
        JujutsuSettings.getInstance(projectFx.get()).state.logChangeLimit = 10
    }

    @AfterEach
    fun cleanup() {
        System.clearProperty(PAGED_LOG_LOAD_PROPERTY)
        JujutsuSettings.getInstance(projectFx.get()).state.logChangeLimit = 500
        JujutsuSettings.getInstance(projectFx.get()).state.logRevset = "all()"
        drainBackgroundLoads(2_000)
    }

    /** Records every revset [getLogHeads] is called with, in order — see the class doc. */
    private class FakeLogService(private val chain: List<LogEntry>) : LogService by mockk(relaxed = true) {
        val headsRevsets = CopyOnWriteArrayList<String>()

        override fun getLogHeads(revset: Revset): Result<List<ChangeId>> {
            headsRevsets.add(revset.toString())
            return Result.success(listOf(chain.first().id))
        }

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
            return Result.success(chain.drop(startIndex).take(limit ?: chain.size))
        }
    }

    /** [storedDepths] records the size of every [LogCache.store] call — see the third test below. */
    private class FakeRepo(val repo: JujutsuRepository, val logService: FakeLogService, val storedDepths: List<Int>)

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
        val storedDepths = CopyOnWriteArrayList<Int>()
        every { repo.logCache } returns mockk<LogCache>(relaxed = true) {
            every { store(any()) } answers { storedDepths.add(firstArg<List<LogEntry>>().size) }
        }
        return FakeRepo(repo, logService, storedDepths)
    }

    private fun loader(repo: JujutsuRepository): UnifiedJujutsuLogDataLoader {
        val panel = mockk<CommitTablePanel<UnifiedJujutsuLogDataLoader.Data>>(relaxed = true)
        return UnifiedJujutsuLogDataLoader(projectFx.get(), { listOf(repo) }, panel)
    }

    @Test
    fun `refresh reseeds an already-paged window when the repo's revset setting changes`() {
        val settings = JujutsuSettings.getInstance(projectFx.get())
        val fake = fakeChainRepo("a", length = 30)
        val log = loader(fake.repo)

        log.loadCommits()
        drainBackgroundLoads(1_000)
        fake.logService.headsRevsets.last() shouldContain "all()"

        settings.state.logRevset = "description(substring-i:\"only-this\")"
        log.refresh()
        drainBackgroundLoads(1_000)

        // A reseed re-runs getLogHeads with the NEW revset - the pre-fix behavior instead kept
        // reusing the old window (baked in at "all()"), so this call would never happen and only
        // getLog's present(...)-based page fetch against the stale window would run.
        fake.logService.headsRevsets.last() shouldContain "only-this"
        fake.logService.headsRevsets.last() shouldNotContain "all()"
    }

    @Test
    fun `refresh reseeds an already-paged window when the repo's limit setting changes`() {
        val settings = JujutsuSettings.getInstance(projectFx.get())
        val fake = fakeChainRepo("a", length = 30)
        val log = loader(fake.repo)

        log.loadCommits()
        drainBackgroundLoads(1_000)
        val headsCallsBefore = fake.logService.headsRevsets.size

        settings.state.logChangeLimit = 3
        log.refresh()
        drainBackgroundLoads(1_000)

        // A limit-only change also invalidates the window (PagedLogWindow.pageSize is likewise
        // fixed at construction) - the reseed path re-runs getLogHeads even though the revset text
        // itself didn't change.
        (fake.logService.headsRevsets.size > headsCallsBefore) shouldBe true
    }

    @Test
    fun `refresh preserves already-loaded deeper pages when nothing changed`() {
        val fake = fakeChainRepo("a", length = 30)
        val log = loader(fake.repo)

        log.loadCommits() // page 1: 10 entries
        drainBackgroundLoads(1_000)
        log.loadMore() // page 2: 20 entries total
        drainBackgroundLoads(1_000)
        fake.storedDepths shouldContain 20

        log.refresh() // nothing changed - must splice a fresh page 1 back onto the existing page 2,
        // not reseed from scratch (which would silently collapse the view back to just page 1 -
        // the same regression class as jj-idea-vqpn's, just via an over-eager fix instead of the
        // original stale-window bug).
        drainBackgroundLoads(1_000)

        fake.storedDepths.last() shouldBe 20
    }
}
