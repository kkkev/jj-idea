package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.OperationId
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for [opLogArgs] and [opRevertArgs] — the argument building logic used by
 * [CliExecutor.opLog] and [CliExecutor.opRevert].
 */
class CliExecutorOpTest {
    @Test
    fun `op log always passes --no-graph and --ignore-working-copy`() {
        val result = opLogArgs(10)

        result.args shouldBe listOf("op", "log", "-n", "10", "--no-graph", "--ignore-working-copy")
        result.reversibility shouldBe Reversibility.READ_ONLY
    }

    @Test
    fun `op log with a template appends -T`() {
        val result = opLogArgs(5, "id ++ description")

        result.args shouldBe
            listOf("op", "log", "-n", "5", "--no-graph", "--ignore-working-copy", "-T", "id ++ description")
    }

    @Test
    fun `op revert default scope is repo, never remote-tracking`() {
        val result = opRevertArgs(OperationId("abc123"), setOf(CommandExecutor.OpRevertScope.REPO))

        result.args shouldBe listOf("op", "revert", "abc123", "--what", "repo")
        result.args shouldNotContain "remote-tracking"
        result.reversibility shouldBe Reversibility.REVERSIBLE
    }

    @Test
    fun `op revert with both scopes emits --what twice`() {
        val result = opRevertArgs(
            OperationId("abc123"),
            setOf(CommandExecutor.OpRevertScope.REPO, CommandExecutor.OpRevertScope.REMOTE_TRACKING)
        )

        result.args shouldBe listOf("op", "revert", "abc123", "--what", "repo", "--what", "remote-tracking")
    }

    @Test
    fun `op revert with no scopes emits no --what flags`() {
        val result = opRevertArgs(OperationId("abc123"), emptySet())

        result.args shouldBe listOf("op", "revert", "abc123")
    }
}
