package `in`.kkkev.jjidea.jj

import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.cli.Reversibility
import `in`.kkkev.jjidea.jj.cli.describeArgs
import `in`.kkkev.jjidea.jj.cli.editArgs
import `in`.kkkev.jjidea.jj.cli.toCommandResult
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

private const val DIVERGENT_STDERR = "Error: Change ID `nrqnpoqonrqn` is divergent\n" +
    "Hint: Use change offset to select a single revision: `nrqnpoqonrqn/0`, `nrqnpoqonrqn/1`\n" +
    "Hint: Use `change_id(nrqnpoqonrqn)` to select all revisions."

/** jj-idea-wiz0: divergent-change errors on writes are typed centrally and never retried. */
class DivergentChangeTest {
    @Test
    fun `jj's divergent error at exit 1 yields the change id`() {
        divergentChangeId(1, DIVERGENT_STDERR) shouldBe "nrqnpoqonrqn"
    }

    @Test
    fun `exit 1 without the divergent text is not divergent`() {
        divergentChangeId(1, "Error: Revision `zzz` doesn't exist") shouldBe null
    }

    @Test
    fun `the divergent text at another exit code is not divergent`() {
        divergentChangeId(2, DIVERGENT_STDERR) shouldBe null
    }

    @Test
    fun `toCommandResult maps a divergent failure to the typed kind`() {
        val result = ProcessOutput("", DIVERGENT_STDERR, 1, false, false)
            .toCommandResult("describe", 30_000, Reversibility.REVERSIBLE)

        result.shouldBeInstanceOf<CommandResult.Failure.Divergent>().changeId shouldBe "nrqnpoqonrqn"
    }

    @Test
    fun `toCommandResult leaves other exit-1 failures as Exited`() {
        ProcessOutput("", "boom", 1, false, false)
            .toCommandResult("describe", 30_000, Reversibility.REVERSIBLE)
            .shouldBeInstanceOf<CommandResult.Failure.Exited>()
    }

    @Test
    fun `writes address the change id with offset, not a commit id`() {
        val divergent = ChangeId("nrqnpoqonrqn", "nr", 1)
        describeArgs(Description("x"), divergent).args shouldBe
            listOf("describe", "-r", "nrqnpoqonrqn/1", "--message=x")
        editArgs(divergent).args shouldBe listOf("edit", "nrqnpoqonrqn/1")
    }
}

/** Command-level routing: Divergent reaches the handler, skips onFailure, and never re-runs the action. */
@Tag("platform")
@TestApplication
@RunInEdt
class CommandDivergentInterceptTest {
    private val repo = mockk<JujutsuRepository> { every { project } returns mockk<Project>() }
    private val divergent = CommandResult.Failure.Divergent("", DIVERGENT_STDERR, "nrqnpoqonrqn")

    @Test
    fun `a divergent failure goes to the handler once, skips onFailure and does not retry`() {
        var runs = 0
        var onFailureCalled = false
        val handled = mutableListOf<CommandResult.Failure.Divergent>()

        CommandExecutor.Command.WithRepo(
            repo,
            mockk(),
            action = {
                runs++
                divergent
            },
            divergenceHandler = { _, failure -> handled += failure }
        ).onFailure { onFailureCalled = true }.executeAsync()
        drainBackgroundLoads()

        handled shouldBe listOf(divergent)
        onFailureCalled shouldBe false
        runs shouldBe 1
    }
}
