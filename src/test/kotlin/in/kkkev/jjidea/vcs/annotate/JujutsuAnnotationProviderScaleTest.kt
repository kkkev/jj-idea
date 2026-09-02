package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.commandResult
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Operation-count scale test for jj-idea-1sza: preloading must stop issuing `jj file annotate`
 * processes once a repository's annotate calls are consistently slow.
 *
 * Before this change, [JujutsuAnnotationProvider.populateCache] called `annotate(file)`
 * unconditionally, so preloading N opened files on a slow repo cost O(N) full `jj file annotate`
 * invocations (each up to the 120s `annotateTimeout`) regardless of whether the user ever opened
 * the gutter. After this change, once the rolling average annotate duration crosses the backoff
 * threshold, further preloads are O(1) (a map lookup, no process spawned) — while the on-demand
 * gutter action ([JujutsuAnnotationProvider.annotateInternal]) is never gated.
 *
 * Follows the injected-counting-collaborator pattern of `jj/RepoLogCacheScaleTest.kt`: a mockk
 * [CommandExecutor] counts real annotate invocations, and a fake clock stands in for wall-clock
 * time so the test is deterministic (no `Thread.sleep`).
 */
class JujutsuAnnotationProviderScaleTest {
    private val project = mockk<Project>()
    private val vcs = JujutsuVcs(project)
    private val repo = mockk<JujutsuRepository>()
    private val commandExecutor = mockk<CommandExecutor>()
    private val file = MockVirtualFile("test.txt")

    private var clockMs = 0L
    private var annotateCallCount = 0

    private fun providerAdvancingClockBy(stepMs: Long): JujutsuAnnotationProvider {
        every { repo.commandExecutor } returns commandExecutor
        every { repo.directory } returns MockVirtualFile("repo")
        every { repo.workingCopy } returns LogEntry(
            repo = repo,
            id = ChangeId("wc", "wc"),
            commitId = CommitId("wc-commit"),
            underlyingDescription = ""
        )
        every { commandExecutor.annotate(any(), any(), any()) } answers {
            annotateCallCount++
            clockMs += stepMs
            commandResult(exitCode = 0, stdout = "", stderr = "")
        }
        return JujutsuAnnotationProvider(project, vcs, nowMs = { clockMs })
    }

    /**
     * Simulates [JujutsuAnnotationProvider.populateCache]'s check-then-preload sequence without
     * driving the full [JujutsuAnnotationProvider.annotate] resolution path (content locator, log
     * service, working-copy lookup, etc. — see `isPreloadBackedOff`'s doc comment).
     */
    private fun simulatePreload(provider: JujutsuAnnotationProvider) {
        if (provider.isPreloadBackedOff(repo)) return
        provider.annotateInternal(file, WorkingCopy, repo)
    }

    @Test
    fun `preloading a slow repo backs off after one sample, bounding annotate calls at O(1)`() {
        // 10s per call is far above the 5s backoff threshold: the very first sample should trip it.
        val provider = providerAdvancingClockBy(stepMs = 10_000)

        repeat(50) { simulatePreload(provider) }

        // Bounded constant, not O(N=50): only the first sample (which seeds the average) issues
        // a real annotate call.
        annotateCallCount shouldBeLessThan 5
        annotateCallCount shouldBe 1
    }

    @Test
    fun `preloading a fast repo keeps issuing one annotate call per open`() {
        // 50ms per call is far below the 5s threshold: backoff never trips.
        val provider = providerAdvancingClockBy(stepMs = 50)

        repeat(50) { simulatePreload(provider) }

        annotateCallCount shouldBe 50
    }

    @Test
    fun `on-demand annotate is never gated, even after preload backs off`() {
        val provider = providerAdvancingClockBy(stepMs = 10_000)

        // Trip the backoff via (simulated) preloading.
        repeat(10) { simulatePreload(provider) }
        provider.isPreloadBackedOff(repo) shouldBe true
        annotateCallCount shouldBe 1

        // The on-demand gutter action calls annotateInternal directly — it must still invoke the
        // executor regardless of the preload backoff state.
        provider.annotateInternal(file, WorkingCopy, repo)

        annotateCallCount shouldBe 2
        verify(exactly = 2) { commandExecutor.annotate(any(), any(), any()) }
    }
}
