package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/** `quiet` reaches `jj log`, so a half-typed revset in the log filter doesn't log a WARN per keystroke. */
class CliLogServiceQuietTest {
    private val failure = CommandExecutor.CommandResult.Failure.Exited("", "Error: bad revset", 1)
    private val executor = mockk<CommandExecutor> {
        // Concrete revsets: MockK can't match `any()` for the Revset value class.
        every { log(Expression("x("), any(), any(), any(), any()) } returns failure
        every { log(Expression("heads(x()"), any(), any(), any(), any()) } returns failure
    }
    private val service = CliLogService(
        mockk<JujutsuRepository> {
            every { commandExecutor } returns executor
            every { directory } returns mockk { every { path } returns "/repo" }
        }
    )

    @Test
    fun `quiet getLogHeads runs jj log quietly`() {
        service.getLogHeads(Expression("x("), quiet = true)

        verify { executor.log(Expression("heads(x()"), any(), any(), any(), true) }
    }

    @Test
    fun `quiet getChangeIds runs jj log quietly`() {
        service.getChangeIds(Expression("x("), quiet = true)

        verify { executor.log(Expression("x("), any(), any(), any(), true) }
    }

    @Test
    fun `failures are loud by default`() {
        service.getLogHeads(Expression("x("))

        verify { executor.log(Expression("heads(x()"), any(), any(), any(), false) }
    }
}
