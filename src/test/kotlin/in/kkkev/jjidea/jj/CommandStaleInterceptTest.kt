package `in`.kkkev.jjidea.jj

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * jj-idea-27b4: [CommandExecutor.Command] must classify a [CommandExecutor.CommandResult.Failure]
 * before ever reaching the call site's own `onFailure` - a stale-workspace failure gets the
 * "Update Stale Workspace" remedy ([JujutsuNotifications.notifyWorkingCopyUnavailable]) instead of
 * whatever generic/mis-parsing handling the call site wrote. This was the root cause behind
 * Advance Bookmark, Rename/Create Bookmark, and other actions showing raw jj stderr (or a flatly
 * wrong message) instead of the stale-workspace notification when the workspace was actually
 * stale, even though jj-idea-b65g's `repo.workingCopy`-based fix was in place.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class CommandStaleInterceptTest {
    private val project = mockk<Project>()
    private val repo = mockk<JujutsuRepository>()

    init {
        every { repo.project } returns project
    }

    private val staleFailure = CommandExecutor.CommandResult.Failure.Exited(
        stdout = "",
        stderr = "Error: Could not read working copy's operation.\n" +
            "Hint: Run `jj workspace update-stale` to recover.",
        exitCode = 1
    )
    private val unrelatedFailure = CommandExecutor.CommandResult.Failure.Exited(
        stdout = "",
        stderr = "Error: Bookmark already exists: foo",
        exitCode = 1
    )

    @AfterEach
    fun tearDown() = unmockkObject(JujutsuNotifications)

    @Test
    fun `a stale-workspace failure notifies instead of calling onFailure`() {
        mockkObject(JujutsuNotifications)
        every { JujutsuNotifications.notifyWorkingCopyUnavailable(any(), any(), any(), any()) } returns Unit
        var onFailureCalled = false

        CommandExecutor.Command(repo, mockk(), action = { staleFailure })
            .onFailure { onFailureCalled = true }
            .executeAsync()
        drainBackgroundLoads()

        onFailureCalled shouldBe false
        verify(exactly = 1) {
            JujutsuNotifications.notifyWorkingCopyUnavailable(
                project,
                repo,
                RepositoryHealth.Stale(staleFailure.stderr, null),
                any()
            )
        }
    }

    @Test
    fun `retry re-runs the exact same command`() {
        mockkObject(JujutsuNotifications)
        var retryCaptured: (() -> Unit)? = null
        every { JujutsuNotifications.notifyWorkingCopyUnavailable(any(), any(), any(), captureLambda()) } answers {
            retryCaptured = lambda<() -> Unit>().captured
        }
        var runs = 0

        CommandExecutor.Command(repo, mockk(), action = {
            runs++
            staleFailure
        })
            .executeAsync()
        drainBackgroundLoads()

        runs shouldBe 1
        retryCaptured?.invoke()
        drainBackgroundLoads()

        runs shouldBe 2
    }

    @Test
    fun `a non-stale failure still calls onFailure as before`() {
        mockkObject(JujutsuNotifications)
        var failureSeen: CommandExecutor.CommandResult.Failure? = null

        CommandExecutor.Command(repo, mockk(), action = { unrelatedFailure })
            .onFailure { failureSeen = this }
            .executeAsync()
        drainBackgroundLoads()

        failureSeen shouldBe unrelatedFailure
        verify(exactly = 0) { JujutsuNotifications.notifyWorkingCopyUnavailable(any(), any(), any(), any()) }
    }

    @Test
    fun `a null repo (rootless command) always calls onFailure, never the stale intercept`() {
        mockkObject(JujutsuNotifications)
        var failureSeen: CommandExecutor.CommandResult.Failure? = null

        CommandExecutor.Command(repo = null, commandExecutor = mockk(), action = { staleFailure })
            .onFailure { failureSeen = this }
            .executeAsync()
        drainBackgroundLoads()

        failureSeen shouldBe staleFailure
        verify(exactly = 0) { JujutsuNotifications.notifyWorkingCopyUnavailable(any(), any(), any(), any()) }
    }
}
