package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.diffedit.DiffEditTool
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * jj-idea-5g8h: an UNticked file deletion in a hunk-level split must land in the FIRST commit as a
 * real deletion (via [DiffEditTool]'s deletion manifest), not as an empty file. Drives the real
 * production staging-tree helper against real `jj split --tool`, with the staging contents the
 * Split dialog now builds (deletion in the manifest, no content entry; picked remainder for the
 * partially-selected file).
 */
@Tag("contract")
@RequiresJj
class SplitHunkDeletionContractCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
    }

    @Test
    fun `unticked deletion becomes a deletion in the first commit, not an empty file`() {
        jj.createFile("a.txt", "one\ntwo\nthree\n")
        jj.createFile("gone.txt", "doomed\n")
        jj.describe("base")
        jj.newChange("to split")
        jj.createFile("a.txt", "ONE\ntwo\nTHREE\n")
        tempDir.resolve("gone.txt").toFile().delete()

        val result = DiffEditTool.withStagingTree(
            perFileContent = mapOf("a.txt" to "ONE\ntwo\nthree\n", "gone.txt" to null),
            deletedPaths = setOf("gone.txt")
        ) { configArgs, tool ->
            val args = buildList {
                configArgs.forEach { add("--config"); add(it) }
                addAll(listOf("split", "-r", "@", "--message=first", "--tool=$tool"))
            }
            jj.run(*args.toTypedArray())
        }
        check(result.isSuccess) { "jj split failed: ${result.stderr}" }

        val firstSummary = jj.run("diff", "-r", "@-", "--summary").stdout
        firstSummary shouldContain "D gone.txt"
        firstSummary shouldContain "M a.txt"
        jj.run("file", "show", "-r", "@-", "a.txt").stdout shouldBe "ONE\ntwo\nthree\n"
        // No empty gone.txt anywhere in the first commit's tree.
        jj.run("file", "list", "-r", "@-").stdout.lines().contains("gone.txt") shouldBe false
        // The remaining hunk stays in the second commit.
        jj.run("diff", "-r", "@", "--summary").stdout shouldContain "M a.txt"
    }
}
