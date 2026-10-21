package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
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
import `in`.kkkev.jjidea.jj.RepositoryHealth
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * jj-idea-dndo / jj-idea-vwxu: a stale working copy reaching [UnifiedJujutsuLogDataLoader] must never escape as an
 * uncaught background exception (which the platform test logger turns into a failure, as in the IDE error dialog),
 * and must surface the update-stale remedy instead of only a log line.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class LogLoaderStaleWorkspaceTest {
    private val projectFx = projectFixture()

    @AfterEach
    fun cleanup() {
        drainBackgroundLoads(2_000)
    }

    private val staleMessage = "Error from jj log: Error: The working copy is stale (not updated since operation " +
        "4952f5bff1aa). Hint: Run `jj workspace update-stale`."

    /** Answers every fetch with [failure] when set, else a one-entry log. */
    private class FakeLogService(private val entry: () -> LogEntry) : LogService by mockk(relaxed = true) {
        @Volatile
        var failure: String? = null

        override fun getLogHeads(revset: Revset, quiet: Boolean): Result<List<ChangeId>> =
            failure?.let { Result.failure(VcsException(it)) } ?: Result.success(listOf(entry().id))

        override fun getLog(
            revset: Revset,
            filePaths: List<FilePath>,
            limit: Int?,
            quiet: Boolean
        ): Result<List<LogEntry>> =
            failure?.let { Result.failure(VcsException(it)) } ?: Result.success(listOf(entry()))
    }

    private class Fixture(val repo: JujutsuRepository, val logService: FakeLogService, val cache: LogCache)

    private fun fakeRepo(): Fixture {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        every { repo.displayName } returns "fake"
        every { repo.directory } returns mockk<VirtualFile>(relaxed = true) { every { path } returns "/fake" }
        val entry = LogEntry(
            repo = repo,
            id = ChangeId(full = "fake-0", short = "fake-0"),
            commitId = CommitId("commit-fake-0"),
            underlyingDescription = "desc",
            parentIds = emptyList()
        )
        val logService = FakeLogService { entry }
        val cache = mockk<LogCache>(relaxed = true)
        every { repo.logService } returns logService
        every { repo.logCache } returns cache
        return Fixture(repo, logService, cache)
    }

    private class Loader(val loader: UnifiedJujutsuLogDataLoader, val notified: MutableList<RepositoryHealth>)

    private fun loader(fixture: Fixture): Loader {
        val panel = mockk<CommitTablePanel<UnifiedJujutsuLogDataLoader.Data>>(relaxed = true)
        val notified = CopyOnWriteArrayList<RepositoryHealth>()
        val loader = UnifiedJujutsuLogDataLoader(projectFx.get(), { listOf(fixture.repo) }, panel).also {
            it.trickleEnabled = false
            it.notifyStale = { _, _, health -> notified += health }
        }
        return Loader(loader, notified)
    }

    @Test
    fun `initial load of a stale workspace offers the remedy and logs no error`() {
        val fixture = fakeRepo()
        fixture.logService.failure = staleMessage
        every { fixture.cache.reload() } throws VcsException(staleMessage)
        val l = loader(fixture)

        l.loader.loadCommits()
        drainBackgroundLoads(2_000)

        (l.notified.isNotEmpty()) shouldBe true
        l.notified.all { it is RepositoryHealth.Stale } shouldBe true
    }

    @Test
    fun `a non-stale failure is not offered the stale remedy`() {
        val fixture = fakeRepo()
        fixture.logService.failure = "Error: something else broke"
        every { fixture.cache.reload() } throws VcsException("Error: something else broke")
        val l = loader(fixture)

        l.loader.loadCommits()
        drainBackgroundLoads(2_000)

        l.notified.size shouldBe 0
    }

    @Test
    fun `a merge over a stale cache does not escape as an uncaught exception`() {
        val fixture = fakeRepo()
        val l = loader(fixture)
        l.loader.loadCommits()
        drainBackgroundLoads(2_000)

        // The cache snapshot is gone (e.g. cleared by a VCS configuration change) and jj now reports stale.
        every { fixture.cache.all } throws VcsException(staleMessage)
        l.loader.applyFilter(LogFilterState()) // any mergeAndNotify() caller reaches the same full merge
        drainBackgroundLoads(2_000)

        (l.notified.isNotEmpty()) shouldBe true
        l.notified.all { it is RepositoryHealth.Stale } shouldBe true
    }

    @Test
    fun `loadExpanding over a stale workspace does not escape as an uncaught exception`() {
        val fixture = fakeRepo()
        every { fixture.cache.loadContext(any(), any()) } throws VcsException(staleMessage)
        val l = loader(fixture)

        l.loader.loadExpanding(fixture.repo, ChangeId(full = "fake-0", short = "fake-0"))
        drainBackgroundLoads(2_000)

        l.notified.size shouldBe 1
    }
}
