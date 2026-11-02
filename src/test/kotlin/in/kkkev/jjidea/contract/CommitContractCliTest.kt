package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.diffedit.DiffEditTool
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
 * Hunk-level `jj split --tool` contracts. Runs only against real jj: the stub backend doesn't model `--tool`.
 *
 * jj-idea-5g8h: an UNticked file deletion in a hunk-level split must land in the FIRST commit as a
 * real deletion (via [DiffEditTool]'s deletion manifest), not as an empty file. Drives the real
 * production staging-tree helper against real `jj split --tool`, with the staging contents the
 * Split dialog now builds (deletion in the manifest, no content entry; picked remainder for the
 * partially-selected file).
 */
@Tag("contract")
@RequiresJj
class SplitHunkContractCliTest {
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
                configArgs.forEach {
                    add("--config")
                    add(it)
                }
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

    // -- `jj split -B --tool` (jj-idea-p6bo, GitHub #139/#132) --
    //
    // The staging tree ($right) is the *selected* content and always lands in the LOWER commit:
    // the new parent under -B (the source keeps its change ID and the full "after" content).

    private fun changeId(revset: String) = jj.run("log", "-r", revset, "--no-graph", "-T", "change_id").stdout.trim()

    private fun splitBWithStaging(perFileContent: Map<String, String?>, deletedPaths: Set<String> = emptySet()) =
        DiffEditTool.withStagingTree(perFileContent, deletedPaths) { configArgs, tool ->
            val args = buildList {
                configArgs.forEach {
                    add("--config")
                    add(it)
                }
                addAll(listOf("split", "-r", "@", "-B", "@", "--message=new parent", "--tool=$tool"))
            }
            jj.run(*args.toTypedArray())
        }

    @Test
    fun `split -B --tool writes the staging tree into the new parent, the source keeps the rest`() {
        jj.createFile("a.txt", "one\ntwo\nthree\n")
        jj.describe("base")
        jj.newChange("to split")
        jj.createFile("a.txt", "ONE\ntwo\nTHREE\n")
        val originalId = changeId("@")

        val result = splitBWithStaging(mapOf("a.txt" to "ONE\ntwo\nthree\n"))
        check(result.isSuccess) { "jj split -B failed: ${result.stderr}" }

        changeId("@") shouldBe originalId
        changeId("@-") shouldNotBe originalId
        jj.run("log", "-r", "@-", "--no-graph", "-T", "description").stdout shouldBe "new parent\n"
        jj.run("file", "show", "-r", "@-", "a.txt").stdout shouldBe "ONE\ntwo\nthree\n"
        jj.run("file", "show", "-r", "@", "a.txt").stdout shouldBe "ONE\ntwo\nTHREE\n"
        // Only the third line differs in the source now.
        val sourceDiff = jj.run("diff", "-r", "@", "--git").stdout
        sourceDiff shouldContain "-three"
        sourceDiff shouldContain "+THREE"
        sourceDiff shouldNotContain "-one"
    }

    @Test
    fun `split -B --tool writes a ticked deletion into the new parent as a real deletion`() {
        jj.createFile("a.txt", "one\ntwo\nthree\n")
        jj.createFile("gone.txt", "doomed\n")
        jj.describe("base")
        jj.newChange("to split")
        jj.createFile("a.txt", "ONE\ntwo\nTHREE\n")
        tempDir.resolve("gone.txt").toFile().delete()

        val result = splitBWithStaging(mapOf("a.txt" to "ONE\ntwo\nthree\n", "gone.txt" to null), setOf("gone.txt"))
        check(result.isSuccess) { "jj split -B failed: ${result.stderr}" }

        val parentSummary = jj.run("diff", "-r", "@-", "--summary").stdout
        parentSummary shouldContain "D gone.txt"
        parentSummary shouldContain "M a.txt"
        jj.run("file", "list", "-r", "@-").stdout.lines().contains("gone.txt") shouldBe false
        jj.run("diff", "-r", "@", "--summary").stdout shouldNotContain "gone.txt"
    }
}
