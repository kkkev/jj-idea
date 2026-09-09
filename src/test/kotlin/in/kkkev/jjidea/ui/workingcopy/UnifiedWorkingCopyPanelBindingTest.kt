package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RepositoryHealth
import `in`.kkkev.jjidea.jj.WorkingCopyUnavailableException
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression test for jj-idea-4d7p: the Working Copy panel's `workingCopies` handler used to
 * `return@connect` (dropping the update entirely) whenever [in.kkkev.jjidea.jj.JjAvailabilityChecker]
 * hadn't resolved yet. Since [in.kkkev.jjidea.util.SimpleNotifiableState] only republishes on
 * change, a dropped update was never resent, leaving the panel permanently unbound - empty
 * description box, every toolbar button disabled - until the next real working-copy change. Fixed
 * by narrowing the availability gate to just the card (content/empty/not-installed) switch;
 * binding the repository no longer depends on it.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class UnifiedWorkingCopyPanelBindingTest {
    private val project = projectFixture()

    @AfterEach
    fun drainStateModelLoads() = drainBackgroundLoads()

    @Test
    fun `binds a repository from a working-copies update even before jj availability resolves`() {
        val panel = UnifiedWorkingCopyPanel(project.get())
        try {
            val repo = mockk<JujutsuRepository>()
            val entry = LogEntry(
                repo = repo,
                id = ChangeId("qpvuntsm", "qp", 2),
                commitId = CommitId("abc123"),
                underlyingDescription = "in progress"
            )
            every { repo.displayName } returns "my-repo"
            every { repo.workingCopy } returns entry
            every { repo.directory } returns mockk<VirtualFile> { every { path } returns "/repo" }

            // No availability status is stubbed/set up here on purpose: the fixture's real
            // JjAvailabilityChecker starts at Checking and this call must not depend on it having
            // resolved to Available yet - that's exactly the race being fixed.
            panel.onWorkingCopiesChanged(mapOf("/repo" to entry))

            panel.boundRepository shouldBe repo
        } finally {
            Disposer.dispose(panel)
        }
    }

    /**
     * Regression test for jj-idea-b65g: a workingCopies update that drops the currently-bound
     * repo (e.g. its workspace just went stale) used to read the throwing
     * [in.kkkev.jjidea.jj.JujutsuRepository.workingCopy] for it directly - an uncaught
     * [WorkingCopyUnavailableException] here would have broken this update entirely, even though
     * another repo is still healthy. It now reads the update's own map, so a dropped repo is
     * simply skipped for this refresh (and the panel then rebinds to the still-healthy repo, its
     * existing fallback for "the bound repo is gone").
     */
    @Test
    fun `dropping the bound repo from a workingCopies update doesn't crash, even with a healthy repo still present`() {
        val panel = UnifiedWorkingCopyPanel(project.get())
        try {
            val repoA = mockk<JujutsuRepository>()
            val entryA = LogEntry(repoA, ChangeId("aaa", "a", 1), CommitId("a1"), underlyingDescription = "")
            every { repoA.displayName } returns "repo-a"
            every { repoA.directory } returns mockk<VirtualFile> { every { path } returns "/repo-a" }
            every { repoA.workingCopy } throws
                WorkingCopyUnavailableException(repoA, RepositoryHealth.Stale("stale", "op1"))

            val repoB = mockk<JujutsuRepository>()
            val entryB = LogEntry(repoB, ChangeId("bbb", "b", 1), CommitId("b1"), underlyingDescription = "")
            every { repoB.displayName } returns "repo-b"
            every { repoB.directory } returns mockk<VirtualFile> { every { path } returns "/repo-b" }
            every { repoB.workingCopy } returns entryB

            // Bind repo-a while it's still healthy (its workingCopy read only starts throwing below).
            every { repoA.workingCopy } returns entryA
            panel.onWorkingCopiesChanged(mapOf("/repo-a" to entryA))
            panel.boundRepository shouldBe repoA

            // repo-a's workspace has now gone stale - dropped from the update; repo-b is healthy.
            every { repoA.workingCopy } throws
                WorkingCopyUnavailableException(repoA, RepositoryHealth.Stale("stale", "op1"))
            panel.onWorkingCopiesChanged(mapOf("/repo-b" to entryB))

            // Falls back to the remaining healthy repo - onWorkingCopiesChanged's existing
            // "bound repo is gone" rebind, now reachable without first throwing.
            panel.boundRepository shouldBe repoB
        } finally {
            Disposer.dispose(panel)
        }
    }

    /**
     * Regression test for jj-idea-b65g: [UnifiedWorkingCopyPanel.uiDataSnapshot] used to read the
     * throwing `workingCopy` for the bound repo directly - called from the platform's data-context
     * caching path, an uncaught exception there breaks action updates for the whole component, not
     * just this one data key.
     */
    @Test
    fun `uiDataSnapshot doesn't crash when the bound repo's working copy is unavailable`() {
        val panel = UnifiedWorkingCopyPanel(project.get())
        try {
            val repo = mockk<JujutsuRepository>()
            val entry = LogEntry(repo, ChangeId("aaa", "a", 1), CommitId("a1"), underlyingDescription = "")
            every { repo.displayName } returns "repo-a"
            every { repo.directory } returns mockk<VirtualFile> { every { path } returns "/repo-a" }
            every { repo.workingCopy } returns entry
            panel.onWorkingCopiesChanged(mapOf("/repo-a" to entry))
            panel.boundRepository shouldBe repo

            every { repo.workingCopy } throws
                WorkingCopyUnavailableException(repo, RepositoryHealth.Unreadable("broken"))
            val sink = mockk<DataSink>(relaxed = true)

            // Must not throw - this used to read the throwing `workingCopy` directly, from
            // inside the platform's data-context caching path (jj-idea-b65g).
            panel.uiDataSnapshot(sink)
        } finally {
            Disposer.dispose(panel)
        }
    }
}
