package `in`.kkkev.jjidea.jj.cli

import com.intellij.execution.process.ProcessOutput
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Regression tests for jj-idea-6g8r: `CliExecutor.execute` used to build [CommandExecutor.CommandResult]
 * straight from [ProcessOutput] without ever checking [ProcessOutput.isTimeout], so a command killed by
 * the timeout was indistinguishable from any other failure and arrived with empty stderr (jj-idea-hpvu).
 * [toCommandResult] is the extraction point that fixes that; these tests exercise it directly since
 * `CliExecutor` itself has no timeout injection seam.
 */
class CliExecutorTimeoutTest {
    @Test
    fun `timed out output is flagged and gets a real message naming the command and limit`() {
        val output = ProcessOutput("partial stdout", "", -1, true, false)

        val result = output.toCommandResult("file annotate -r @ file.txt", 120_000)

        val timedOut = result.shouldBeInstanceOf<CommandResult.Failure.TimedOut>()
        timedOut.exitCode shouldBe -1
        timedOut.stdout shouldBe "partial stdout"
        timedOut.stderr shouldContain "file annotate -r @ file.txt"
        timedOut.stderr shouldContain "120"
    }

    @Test
    fun `successful output is not flagged as timed out`() {
        val output = ProcessOutput("ok", "", 0, false, false)

        val result = output.toCommandResult("status", 30_000)

        val success = result.shouldBeInstanceOf<CommandResult.Success>()
        success.stdout shouldBe "ok"
        success.stderr shouldBe ""
    }

    @Test
    fun `ordinary failure is not flagged as timed out and stderr passes through verbatim`() {
        val output = ProcessOutput("", "boom", 1, false, false)

        val result = output.toCommandResult("status", 30_000)

        val exited = result.shouldBeInstanceOf<CommandResult.Failure.Exited>()
        exited.exitCode shouldBe 1
        exited.stderr shouldBe "boom"
    }
}
