package `in`.kkkev.jjidea.commands

import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Tests for CommandResult - simplest component
 */
class JujutsuCommandResultTest {
    @Test
    fun `a Success is success`() {
        val result: CommandResult =
            CommandResult.Success.Irreversible(
                stdout = "output",
                stderr = "",
                reason = CommandResult.Success.Irreversible.Reason.NOT_TRACKED
            )

        result.isSuccess shouldBe true
    }

    @Test
    fun `a Failure is not success`() {
        val result: CommandResult = CommandResult.Failure.Exited(stdout = "", stderr = "error", exitCode = 1)

        result.isSuccess shouldBe false
        result.shouldBeInstanceOf<CommandResult.Failure>().exitCode shouldBe 1
    }

    @Test
    fun `CommandResult captures stdout and stderr`() {
        val result: CommandResult =
            CommandResult.Success.Irreversible(
                stdout = "standard output",
                stderr = "standard error",
                reason = CommandResult.Success.Irreversible.Reason.NOT_TRACKED
            )

        result.stdout shouldBe "standard output"
        result.stderr shouldBe "standard error"
    }

    @Test
    fun `Failure message is meaningful for all three failure kinds`() {
        val exited = CommandResult.Failure.Exited(stdout = "", stderr = "boom", exitCode = 1)
        exited.message shouldBe "boom"

        val timedOut = CommandResult.Failure.TimedOut(stdout = "partial", timeoutMillis = 30_000, stderr = "timed out")
        timedOut.message shouldBe "timed out"
        timedOut.stdout shouldBe "partial"
        timedOut.exitCode shouldBe -1

        val notLaunched = CommandResult.Failure.NotLaunched(executable = "jj", stderr = "jj not found")
        notLaunched.message shouldBe "jj not found"
        notLaunched.stdout shouldBe ""
        notLaunched.exitCode shouldBe -1
    }
}
