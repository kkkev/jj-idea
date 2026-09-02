package `in`.kkkev.jjidea.ui.services

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationId
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [withUndoBalloon] - the notifier seam fires exactly once on a
 * [CommandResult.Success.Reversible] result and never otherwise, since anything else means
 * either the command failed or there's nothing safe to offer undo for.
 *
 * `@TestApplication`, not a plain unit test: [withUndoBalloon] posts the notification via
 * [in.kkkev.jjidea.util.runLater], which needs a live `ApplicationManager.getApplication()`.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class UndoBalloonPlatformTest {
    private val undoService = JujutsuUndoService()
    private val project = mockk<Project> { every { getService(JujutsuUndoService::class.java) } returns undoService }
    private val repo = mockk<JujutsuRepository>()
    private val commandExecutor = mockk<CommandExecutor>()

    private data class Notification(val repo: JujutsuRepository, val operation: OperationId, val label: String)

    /** Runs the wrapped action, then pumps the EDT queue so the deferred [notify] call executes. */
    private fun runWithBalloon(result: CommandResult): List<Notification> {
        val notified = mutableListOf<Notification>()
        CommandExecutor.Command(commandExecutor, action = { result })
            .withUndoBalloon(project, repo, "log.action.abandon.undo") { _, r, op, label ->
                notified += Notification(r, op, label)
            }
            .action(commandExecutor)
        UIUtil.dispatchAllInvocationEvents()
        return notified
    }

    @Test
    fun `fires exactly once on Reversible`() {
        val notified = runWithBalloon(CommandResult.Success.Reversible("out", "", OperationId("op1")))

        notified shouldBe listOf(Notification(repo, OperationId("op1"), "Abandon"))
    }

    @Test
    fun `does not fire on Irreversible`() {
        val notified = runWithBalloon(
            CommandResult.Success.Irreversible("out", "", CommandResult.Success.Irreversible.Reason.NOT_TRACKED)
        )

        notified shouldBe emptyList()
    }

    @Test
    fun `does not fire on Failure`() {
        val notified = runWithBalloon(CommandResult.Failure.Exited("", "boom", 1))

        notified shouldBe emptyList()
    }

    @Test
    fun `records the operation in JujutsuUndoService on Reversible`() {
        runWithBalloon(CommandResult.Success.Reversible("out", "", OperationId("op1")))

        undoService.current() shouldBe (repo to JujutsuUndoService.Record(OperationId("op1"), "Abandon"))
    }

    @Test
    fun `does not touch JujutsuUndoService on Irreversible or Failure`() {
        runWithBalloon(CommandResult.Failure.Exited("", "boom", 1))

        undoService.current() shouldBe null
    }
}
