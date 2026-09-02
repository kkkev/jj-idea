package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Real-jj verification of Stage 1 of the undo roadmap (docs/design/undo-support-roadmap.md):
 * [CliExecutor.withUndoTracking] identifies the operation a command wrote via a per-invocation
 * `--config` token, and [CliExecutor.opRevert] reverts exactly that operation. `JjStub` has no
 * op-log concept, so this is CLI-only, [RequiresJj]-gated.
 */
@Tag("contract")
@RequiresJj
class UndoContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli
    private lateinit var executor: CliExecutor

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
        val root = mockk<VirtualFile> { every { path } returns tempDir.toString() }
        executor = CliExecutor(root)
    }

    @Test
    fun `dirty working copy - abandon is tracked to the abandon op, not the paired snapshot`() {
        jj.describe("first change")
        jj.newChange()
        jj.createFile("dirty.txt", "uncommitted edit") // working copy is dirty at abandon time

        val result = executor.withUndoTracking().abandon(WorkingCopy)

        val reversible = result.shouldBeInstanceOf<CommandResult.Success.Reversible>()
        val matchedOp = jj.run(
            "op",
            "log",
            "--no-graph",
            "-T",
            "description",
            "-n",
            "1",
            "--at-operation",
            reversible.operation.toString()
        )
        matchedOp.stdout shouldContain "abandon"
    }

    @Test
    fun `revert restores the abandoned change, and the op log grows rather than shrinks`() {
        jj.describe("to be abandoned")
        val beforeLog = jj.run("log", "--no-graph", "-T", "description", "-r", "all()")
        val opCountBefore = jj.run("op", "log", "--no-graph").stdout.lines().count { it.isNotBlank() }

        val result = executor.withUndoTracking().abandon(WorkingCopy)
        val reversible = result.shouldBeInstanceOf<CommandResult.Success.Reversible>()

        val revertResult = executor.opRevert(reversible.operation)
        revertResult.shouldBeInstanceOf<CommandResult.Success>()

        val afterLog = jj.run("log", "--no-graph", "-T", "description", "-r", "all()")
        afterLog.stdout shouldContain "to be abandoned"
        beforeLog.stdout shouldBe afterLog.stdout

        val opCountAfter = jj.run("op", "log", "--no-graph").stdout.lines().count { it.isNotBlank() }
        (opCountAfter > opCountBefore) shouldBe true
    }

    @Test
    fun `an unrelated op landing first does not confuse identification`() {
        jj.describe("target change")
        jj.run("describe", "-m", "an unrelated decoy op") // some other actor's operation

        val result = executor.withUndoTracking().abandon(WorkingCopy)

        val reversible = result.shouldBeInstanceOf<CommandResult.Success.Reversible>()
        val matchedOp = jj.run(
            "op",
            "log",
            "--no-graph",
            "-T",
            "description",
            "-n",
            "1",
            "--at-operation",
            reversible.operation.toString()
        )
        matchedOp.stdout shouldContain "abandon"
    }

    @Test
    fun `git push is irreversible and makes no extra op log call`() {
        val bareRemote = tempDir.resolveSibling("${tempDir.fileName}-remote.git")
        ProcessBuilder("git", "init", "--bare", bareRemote.toString()).start().waitFor()
        jj.addGitRemote("origin", bareRemote.toString())
        jj.describe("pushed change")
        jj.run("bookmark", "create", "main", "-r", "@")

        var calls = 0
        val trackedExecutor = CliExecutor(
            root = mockk { every { path } returns tempDir.toString() },
            executableProvider = {
                calls++
                "jj"
            }
        ).withUndoTracking()

        val result = trackedExecutor.gitPush(bookmark = `in`.kkkev.jjidea.jj.Bookmark("main"))

        val irreversible = result.shouldBeInstanceOf<CommandResult.Success.Irreversible>()
        irreversible.reason shouldBe CommandResult.Success.Irreversible.Reason.NOT_REVERSIBLE_COMMAND
        calls shouldBe 1 // no follow-up op log read for a command we never token-tagged
    }

    @Test
    fun `describe without withUndoTracking stays untracked`() {
        val result = executor.describe(`in`.kkkev.jjidea.jj.Description("untracked"), WorkingCopy)

        val irreversible = result.shouldBeInstanceOf<CommandResult.Success.Irreversible>()
        irreversible.reason shouldBe CommandResult.Success.Irreversible.Reason.NOT_TRACKED
    }
}
