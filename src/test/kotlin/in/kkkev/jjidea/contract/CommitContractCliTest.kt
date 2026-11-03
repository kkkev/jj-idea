package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.diffedit.DiffEditTool
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.cli.commitArgs
import `in`.kkkev.jjidea.jj.cli.commitInteractiveArgs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * `jj commit` contracts (GitHub #157), against real jj with the production argument builders: the
 * selected changes stay in the working copy's own change ID with the message, and everything else
 * moves to a new working-copy change. The hunk-level tests mirror [SplitHunkContractCliTest]'s
 * `split -B --tool` ones, which have the same polarity. Runs only against real jj: the stub backend
 * doesn't model `jj commit`.
 */
@Tag("contract")
@RequiresJj
class CommitContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
    }

    // -- `jj commit` / `jj commit --tool` --
    //
    // The staging tree ($right) is the *selected* content and always lands in the LOWER commit:
    // the committed change, which keeps the working copy's change ID.

    private fun changeId(revset: String) = jj.run("log", "-r", revset, "--no-graph", "-T", "change_id").stdout.trim()

    private fun commitWithStaging(perFileContent: Map<String, String?>, deletedPaths: Set<String> = emptySet()) =
        DiffEditTool.withStagingTree(perFileContent, deletedPaths) { configArgs, tool ->
            jj.run(*commitInteractiveArgs(Description("committed"), configArgs, tool).args.toTypedArray())
        }

    @Test
    fun `commit keeps the selected files on the working copy's change ID, the rest moves to a new change`() {
        jj.createFile("a.txt", "a\n")
        jj.createFile("b.txt", "b\n")
        val originalId = changeId("@")

        val result = jj.run(*commitArgs(listOf("a.txt"), Description("committed")).args.toTypedArray())
        check(result.isSuccess) { "jj commit failed: ${result.stderr}" }

        changeId("@-") shouldBe originalId
        changeId("@") shouldNotBe originalId
        jj.run("log", "-r", "@-", "--no-graph", "-T", "description").stdout shouldBe "committed\n"
        jj.run("diff", "-r", "@-", "--summary").stdout.trim() shouldBe "A a.txt"
        jj.run("diff", "-r", "@", "--summary").stdout.trim() shouldBe "A b.txt"
    }

    @Test
    fun `commit gives the new working copy a fresh author timestamp`() {
        jj.createFile("a.txt", "a\n")
        jj.createFile("b.txt", "b\n")
        // First snapshot of the edits at 09:00 stamps the working copy's author timestamp.
        jj.run("--config", "debug.commit-timestamp=2026-01-01T09:00:00+00:00", "status").isSuccess shouldBe true

        val args = commitArgs(listOf("a.txt"), Description("committed")).args
        val result = jj.run("--config", "debug.commit-timestamp=2026-01-01T12:00:00+00:00", *args.toTypedArray())
        check(result.isSuccess) { "jj commit failed: ${result.stderr}" }

        val authorTime = """author.timestamp().utc().format("%H:%M")"""
        jj.run("log", "-r", "@", "--no-graph", "-T", authorTime).stdout.trim() shouldBe "12:00"
    }

    @Test
    fun `commit --tool writes the staging tree into the committed change, the rest moves to a new change`() {
        jj.createFile("a.txt", "one\ntwo\nthree\n")
        jj.describe("base")
        jj.newChange()
        jj.createFile("a.txt", "ONE\ntwo\nTHREE\n")
        val originalId = changeId("@")

        val result = commitWithStaging(mapOf("a.txt" to "ONE\ntwo\nthree\n"))
        check(result.isSuccess) { "jj commit --tool failed: ${result.stderr}" }

        changeId("@-") shouldBe originalId
        changeId("@") shouldNotBe originalId
        jj.run("log", "-r", "@-", "--no-graph", "-T", "description").stdout shouldBe "committed\n"
        jj.run("file", "show", "-r", "@-", "a.txt").stdout shouldBe "ONE\ntwo\nthree\n"
        jj.run("file", "show", "-r", "@", "a.txt").stdout shouldBe "ONE\ntwo\nTHREE\n"
        // Only the third line differs in the new working copy.
        val remainingDiff = jj.run("diff", "-r", "@", "--git").stdout
        remainingDiff shouldContain "-three"
        remainingDiff shouldContain "+THREE"
        remainingDiff shouldNotContain "-one"
    }

    @Test
    fun `commit --tool writes a ticked deletion into the committed change as a real deletion`() {
        jj.createFile("a.txt", "one\ntwo\nthree\n")
        jj.createFile("gone.txt", "doomed\n")
        jj.describe("base")
        jj.newChange()
        jj.createFile("a.txt", "ONE\ntwo\nTHREE\n")
        tempDir.resolve("gone.txt").toFile().delete()

        val result = commitWithStaging(mapOf("a.txt" to "ONE\ntwo\nthree\n", "gone.txt" to null), setOf("gone.txt"))
        check(result.isSuccess) { "jj commit --tool failed: ${result.stderr}" }

        val parentSummary = jj.run("diff", "-r", "@-", "--summary").stdout
        parentSummary shouldContain "D gone.txt"
        parentSummary shouldContain "M a.txt"
        jj.run("file", "list", "-r", "@-").stdout.lines().contains("gone.txt") shouldBe false
        jj.run("diff", "-r", "@", "--summary").stdout shouldNotContain "gone.txt"
    }
}
