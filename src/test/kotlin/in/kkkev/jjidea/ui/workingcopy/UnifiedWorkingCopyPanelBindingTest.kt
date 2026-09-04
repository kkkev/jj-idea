package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
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
}
