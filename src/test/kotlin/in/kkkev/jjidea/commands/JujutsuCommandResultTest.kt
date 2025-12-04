package `in`.kkkev.jjidea.commands

import `in`.kkkev.jjidea.jj.JujutsuCommandExecutor
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for CommandResult - simplest component
 */
class JujutsuCommandResultTest {

    @Test
    fun `CommandResult with exit code 0 is success`() {
        val result = JujutsuCommandExecutor.CommandResult(
            exitCode = 0,
            stdout = "output",
            stderr = ""
        )

        result.isSuccess shouldBe true
    }

    @Test
    fun `CommandResult with non-zero exit code is failure`() {
        val result = JujutsuCommandExecutor.CommandResult(
            exitCode = 1,
            stdout = "",
            stderr = "error"
        )

        result.isSuccess shouldBe false
    }

    @Test
    fun `CommandResult captures stdout and stderr`() {
        val result = JujutsuCommandExecutor.CommandResult(
            exitCode = 0,
            stdout = "standard output",
            stderr = "standard error"
        )

        result.stdout shouldBe "standard output"
        result.stderr shouldBe "standard error"
    }
}