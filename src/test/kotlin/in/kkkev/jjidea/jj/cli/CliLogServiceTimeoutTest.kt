package `in`.kkkev.jjidea.jj.cli

import com.intellij.openapi.vcs.VcsException
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JjTimedOutException
import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** jj-idea-1bio: a timed-out `jj log` must surface as [JjTimedOutException], not a plain failure. */
class CliLogServiceTimeoutTest {
    private fun serviceReturning(result: CommandExecutor.CommandResult): CliLogService {
        val executor = mockk<CommandExecutor> {
            every { log(Expression.ALL, any(), any(), any(), any()) } returns result
        }
        val repo = mockk<JujutsuRepository> {
            every { commandExecutor } returns executor
            every { directory } returns mockk { every { path } returns "/repo" }
        }
        return CliLogService(repo)
    }

    @Test
    fun `a timed out jj log fails with JjTimedOutException carrying the timeout`() {
        val service = serviceReturning(
            CommandExecutor.CommandResult.Failure.TimedOut("", 30_000, "jj log timed out after 30 seconds")
        )

        val failure = service.getLog(Expression.ALL).exceptionOrNull()

        failure.shouldBeInstanceOf<JjTimedOutException>()
        failure.timeoutMillis shouldBe 30_000
        failure.timeoutSeconds shouldBe 30
    }

    @Test
    fun `an ordinary jj log failure stays a plain VcsException`() {
        val service = serviceReturning(CommandExecutor.CommandResult.Failure.Exited("", "boom", 1))

        val failure = service.getLog(Expression.ALL).exceptionOrNull()

        failure.shouldBeInstanceOf<VcsException>()
        failure.shouldNotBeInstanceOf<JjTimedOutException>()
    }
}
