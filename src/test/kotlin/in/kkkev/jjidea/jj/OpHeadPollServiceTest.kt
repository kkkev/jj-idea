package `in`.kkkev.jjidea.jj

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.settings.JujutsuSettingsState
import `in`.kkkev.jjidea.util.launchPolling
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class OpHeadPollServiceTest {
    private val path = "/repo/op-head-poll-test"
    private val scope = CoroutineScope(Job())
    private val settings = JujutsuSettings().apply { loadState(JujutsuSettingsState(opHeadPollIntervalSeconds = 10)) }
    private val changed = mutableListOf<JujutsuRepository>()
    private val service = OpHeadPollService(mockk<Project>(relaxed = true), scope).also {
        it.settingsOverride = settings
        it.onChanged = { repo -> changed += repo }
    }

    private val results = ArrayDeque<CommandResult>()
    private val executor = mockk<CommandExecutor> {
        every { opLog(1, OpHeadIds.TEMPLATE) } answers { results.removeFirst() }
    }
    private val repo = mockRepo(path)

    private fun mockRepo(repoPath: String) = mockk<JujutsuRepository> {
        every { directory } returns mockk<VirtualFile> { every { this@mockk.path } returns repoPath }
        every { displayName } returns "repo"
        every { commandExecutor } returns executor
    }

    private fun head(id: String) =
        CommandResult.Success.Irreversible(id + "\n", "", CommandResult.Success.Irreversible.Reason.READ_ONLY)

    private val failed = CommandResult.Failure.Exited("", "network down", 1)

    @AfterEach
    fun tearDown() {
        OpHeadIds.clear()
        scope.coroutineContext[Job]!!.cancel()
        JujutsuRepositoryHealth.markReadable(path)
    }

    @Test
    fun `first probe only primes, a later head change reports the repo once`() {
        results += listOf(head("aaa"), head("aaa"), head("bbb"), head("bbb"))

        service.pollOnce(repo) shouldBe true
        service.pollOnce(repo) shouldBe true
        changed.size shouldBe 0
        service.pollOnce(repo) shouldBe true
        service.pollOnce(repo) shouldBe true
        changed shouldContainExactly listOf(repo)
    }

    @Test
    fun `a failed probe returns false so the poller backs off, and reports nothing`() {
        results += failed

        service.pollOnce(repo) shouldBe false
        changed.size shouldBe 0
    }

    @Test
    fun `skips when off`() {
        settings.state.opHeadPollIntervalSeconds = 0

        service.pollOnce(repo) shouldBe true
        verify(exactly = 0) { executor.opLog(any(), any()) }
    }

    @Test
    fun `skips the tick, without counting a failure, while a plugin command runs on the repo`() {
        var result: Boolean? = null
        RepoActivity.track(path) { result = service.pollOnce(repo) }

        result shouldBe true
        verify(exactly = 0) { executor.opLog(any(), any()) }
    }

    @Test
    fun `skips an unreadable repo without counting a failure`() {
        JujutsuRepositoryHealth.markUnreadable(path, "unreadable")

        service.pollOnce(repo) shouldBe true
        verify(exactly = 0) { executor.opLog(any(), any()) }
    }

    @Test
    fun `an overlapping probe of the same repo is skipped, not queued`() {
        var inner: Boolean? = null
        every { executor.opLog(1, OpHeadIds.TEMPLATE) } answers {
            inner = service.pollOnce(repo) // re-entrant call stands in for the concurrent activation probe
            head("aaa")
        }

        service.pollOnce(repo)

        inner shouldBe true
        verify(exactly = 1) { executor.opLog(any(), any()) }
    }

    @Test
    fun `an operation the fetch already recorded is not reported again`() {
        OpHeadIds.record(path, "aaa")
        OpHeadIds.record(path, "bbb") // the fetch saw it and refreshed
        results += head("bbb")

        service.pollOnce(repo) shouldBe true
        changed.size shouldBe 0
    }

    @Test
    fun `reconcile runs one job per repo, and none when off`() {
        every { executor.opLog(any(), any()) } returns head("aaa")
        service.reconcile(listOf(repo, mockRepo("/repo/other")))
        service.activeRepoCount() shouldBe 2

        settings.state.opHeadPollIntervalSeconds = 0
        service.reconcile(listOf(repo))
        service.activeRepoCount() shouldBe 0
    }

    @Test
    fun `interval is clamped and zero means off`() {
        settings.state.opHeadPollIntervalSeconds = 0
        settings.opHeadPollInterval shouldBe null
        settings.state.opHeadPollIntervalSeconds = 1
        settings.opHeadPollInterval shouldBe 5.seconds
        settings.state.opHeadPollIntervalSeconds = 100000
        settings.opHeadPollInterval shouldBe 600.seconds
        settings.state.opHeadPollIntervalSeconds = -5
        settings.opHeadPollInterval shouldBe null
    }

    @Test
    fun `settings round-trip`() {
        val s = JujutsuSettings()
        s.loadState(JujutsuSettingsState(opHeadPollIntervalSeconds = 7))
        s.state.opHeadPollIntervalSeconds shouldBe 7
        JujutsuSettings().state.opHeadPollIntervalSeconds shouldBe 0
    }

    /**
     * Operation count (contributing.md § Performance & Scale): 10 repos for 60 s virtual at a 10 s interval
     * is one jj process per repo per tick, 60 in all, and none while the IDE is inactive.
     */
    @Test
    fun `scale - ten repos for a minute is at most sixty probes, and none while inactive`() {
        every { executor.opLog(any(), any()) } returns head("aaa")
        val repos = (1..10).map { mockRepo("/repo/scale-$it") }

        pollForVirtualMinute(repos, active = true)
        verify(atMost = 60) { executor.opLog(any(), any()) }
        verify(atLeast = 1) { executor.opLog(any(), any()) }

        io.mockk.clearMocks(executor, answers = false)
        pollForVirtualMinute(repos, active = false)
        verify(exactly = 0) { executor.opLog(any(), any()) }
    }

    private fun pollForVirtualMinute(repos: List<JujutsuRepository>, active: Boolean) = runBlocking {
        val jobs = repos.map { r ->
            var now = Duration.ZERO
            CoroutineScope(coroutineContext).launchPolling(
                "test ${r.directory.path}",
                interval = { 10.seconds },
                shouldRun = { active },
                waiter = {
                    now += it
                    if (now > 60.seconds) currentCoroutineContext().cancel(CancellationException("done"))
                    yield()
                }
            ) { service.pollOnce(r) }
        }
        jobs.forEach { it.join() }
    }
}
