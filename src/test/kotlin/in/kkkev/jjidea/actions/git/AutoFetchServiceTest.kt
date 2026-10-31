package `in`.kkkev.jjidea.actions.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.FetchTimes
import `in`.kkkev.jjidea.jj.GitRemote
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.JujutsuRepositoryHealth
import `in`.kkkev.jjidea.jj.RepoActivity
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.settings.JujutsuSettingsState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

class AutoFetchServiceTest {
    private val path = "/repo/auto-fetch-test"
    private val scope = CoroutineScope(Job())
    private val settings = JujutsuSettings().apply { loadState(JujutsuSettingsState(autoFetchIntervalMinutes = 10)) }
    private val failures = mutableListOf<String>()
    private val changed = mutableListOf<JujutsuRepository>()
    private val service = AutoFetchService(mockk<Project>(relaxed = true), scope).also {
        it.settingsOverride = settings
        it.onFailure = { _, msg -> failures += msg }
        it.onChanged = { repo -> changed += repo }
    }

    private val fetchResults = ArrayDeque<CommandResult>()
    private val opHeads = ArrayDeque<String>()
    private val executor = mockk<CommandExecutor> {
        every { gitFetch(any(), any(), any()) } answers { fetchResults.removeFirst() }
        every { opLog(1, AutoFetchService.OP_HEAD_TEMPLATE) } answers { ok(opHeads.removeFirst() + "\n") }
    }
    private val repo = mockRepo(remotes = listOf(GitRemote("origin", "u")))

    private fun mockRepo(remotes: List<GitRemote>) = mockk<JujutsuRepository> {
        every { directory } returns
            mockk<VirtualFile> { every { this@mockk.path } returns this@AutoFetchServiceTest.path }
        every { displayName } returns "repo"
        every { gitRemotes } returns remotes
        every { commandExecutor } returns executor
    }

    private fun ok(stdout: String = "") = CommandResult.Success.Irreversible(
        stdout,
        "",
        CommandResult.Success.Irreversible.Reason.READ_ONLY
    )

    private val failed = CommandResult.Failure.Exited("", "network down", 1)

    private var t: Instant = Instant.parse("2026-11-01T12:00:00Z")

    init {
        FetchTimes.now = { t }
        FetchTimes.clear()
    }

    @AfterEach
    fun tearDown() {
        FetchTimes.now = Instant::now
        FetchTimes.clear()
        scope.coroutineContext[Job]!!.cancel()
        JujutsuRepositoryHealth.markReadable(path)
    }

    @Test
    fun `first fetch only primes, later op head change invalidates`() {
        fetchResults += listOf(ok(), ok(), ok())
        opHeads += listOf("aaa", "aaa", "bbb")

        service.fetchOnce(repo) shouldBe true
        service.fetchOnce(repo) shouldBe true
        changed.size shouldBe 0
        service.fetchOnce(repo) shouldBe true
        changed shouldContainExactly listOf(repo)
    }

    @Test
    fun `failure notifies once per streak and again after a success`() {
        fetchResults += listOf(failed, failed, ok(), failed)
        opHeads += "aaa"

        service.fetchOnce(repo) shouldBe false
        service.fetchOnce(repo) shouldBe false
        failures shouldContainExactly listOf("network down")
        service.fetchOnce(repo) shouldBe true
        service.fetchOnce(repo) shouldBe false
        failures.size shouldBe 2
    }

    @Test
    fun `skips repos with no remotes without fetching`() {
        service.fetchOnce(mockRepo(remotes = emptyList())) shouldBe true
        verify(exactly = 0) { executor.gitFetch(any(), any(), any()) }
    }

    @Test
    fun `skips when disabled`() {
        settings.state.autoFetchIntervalMinutes = 0
        service.fetchOnce(repo) shouldBe true
        verify(exactly = 0) { executor.gitFetch(any(), any(), any()) }
    }

    @Test
    fun `background fetch never snapshots the working copy`() {
        fetchResults += ok()
        opHeads += "aaa"
        service.fetchOnce(repo)
        verify { executor.gitFetch(null, true, true) }
    }

    @Test
    fun `skips the tick, without counting a failure, while a plugin command runs on the repo`() {
        var result: Boolean? = null
        RepoActivity.track(path) { result = service.fetchOnce(repo) }

        result shouldBe true
        verify(exactly = 0) { executor.gitFetch(any(), any(), any()) }
        failures.size shouldBe 0
    }

    @Test
    fun `fetches again once the plugin command has finished`() {
        RepoActivity.track(path) { }
        fetchResults += ok()
        opHeads += "aaa"
        service.fetchOnce(repo) shouldBe true
        verify(exactly = 1) { executor.gitFetch(any(), any(), any()) }
    }

    @Test
    fun `fetches every remote`() {
        fetchResults += ok()
        opHeads += "aaa"
        service.fetchOnce(repo)
        verify { executor.gitFetch(null, true, true) }
    }

    @Test
    fun `a successful fetch records the time for every remote, a failed one does not`() {
        val remotes = listOf(GitRemote("origin", "u"), GitRemote("upstream", "u"))
        val twoRemotes = mockRepo(remotes)
        fetchResults += failed
        service.fetchOnce(twoRemotes)
        FetchTimes.lastFetched(path) shouldBe null

        fetchResults += ok()
        opHeads += "aaa"
        service.fetchOnce(twoRemotes)
        FetchTimes.lastFetchedByRemote(path).map { it.first } shouldContainExactly listOf("origin", "upstream")
    }

    @Test
    fun `catch-up selects repos never fetched or fetched longer ago than the interval`() {
        service.reconcile(listOf(repo))
        service.staleRepos() shouldContainExactly listOf(repo)

        FetchTimes.record(path, listOf("origin"))
        service.staleRepos() shouldBe emptyList()

        t = t.plusSeconds(11 * 60) // interval is 10 minutes
        service.staleRepos() shouldContainExactly listOf(repo)
    }

    @Test
    fun `catch-up selects nothing when automatic fetch is off`() {
        service.reconcile(listOf(repo))
        settings.state.autoFetchIntervalMinutes = 0
        service.staleRepos() shouldBe emptyList()
    }

    @Test
    fun `manual and automatic fetches share one overlap guard per repo`() {
        // A second fetch for the same repo while one is running is skipped, not queued.
        var inner: Boolean? = null
        fetchResults += ok()
        opHeads += "aaa"
        every { executor.gitFetch(any(), any(), any()) } answers {
            inner = service.fetchOnce(repo) // re-entrant call stands in for the concurrent catch-up
            fetchResults.removeFirst()
        }
        service.fetchOnce(repo)
        inner shouldBe true
        verify(exactly = 1) { executor.gitFetch(any(), any(), any()) }
    }

    @Test
    fun `interval is clamped and zero means off`() {
        settings.state.autoFetchIntervalMinutes = 0
        settings.autoFetchInterval shouldBe null
        settings.state.autoFetchIntervalMinutes = 100000
        settings.autoFetchInterval!!.inWholeMinutes shouldBe 120
        settings.state.autoFetchIntervalMinutes = -5
        settings.autoFetchInterval shouldBe null
    }

    @Test
    fun `settings round-trip`() {
        val s = JujutsuSettings()
        s.loadState(JujutsuSettingsState(autoFetchIntervalMinutes = 7))
        s.state.autoFetchIntervalMinutes shouldBe 7
        JujutsuSettings().state.autoFetchIntervalMinutes shouldBe 0
    }
}
