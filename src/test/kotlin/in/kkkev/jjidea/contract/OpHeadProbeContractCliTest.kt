package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.OpHeadIds
import `in`.kkkev.jjidea.jj.cli.opLogArgs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * jj-idea-j2vl: the op-head poll runs `jj op log -n1` on a timer to notice external operations. Verifies
 * against real jj that the probe's id moves when an operation lands, stays put across read-only commands,
 * and - because it passes `--ignore-working-copy` - never writes an operation of its own, even with
 * uncommitted changes on disk (a snapshot would both defeat the change detection and pollute Undo).
 */
@Tag("contract")
@RequiresJj
class OpHeadProbeContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var repo: JjCli

    @BeforeEach
    fun setUp() {
        repo = JjCli(tempDir)
        repo.init()
    }

    private fun probe() = repo.run(*opLogArgs(1, OpHeadIds.TEMPLATE).args.toTypedArray()).stdout.trim()

    private fun operationCount() = repo
        .run("op", "log", "--no-graph", "--ignore-working-copy", "-T", "\"x\\n\"")
        .stdout.lines().count { it.isNotBlank() }

    @Test
    fun `probe id changes after an operation`() {
        val before = probe()
        before.isNotEmpty() shouldBe true

        check(repo.run("new", "-m", "next").isSuccess)

        (probe() != before) shouldBe true
    }

    @Test
    fun `probe id is stable across read-only commands`() {
        val before = probe()

        check(repo.run("log", "--ignore-working-copy").isSuccess)
        check(repo.run("bookmark", "list", "--ignore-working-copy").isSuccess)

        probe() shouldBe before
    }

    @Test
    fun `probing writes no operation even with uncommitted changes on disk`() {
        repo.createFile("dirty.txt", "x\n")
        val count = operationCount()
        val before = probe()

        repeat(3) { probe() }

        probe() shouldBe before
        operationCount() shouldBe count
    }
}
