package `in`.kkkev.jjidea.integration

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.contract.JjCli
import `in`.kkkev.jjidea.contract.RequiresJj
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.RevisionExpression
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.jj.reconstructMergeParentContent
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Regression tests for jj-idea-gpf5: verifies that [in.kkkev.jjidea.jj.reconstructMergeParentContent] —
 * the "before" reconstruction used for merge commits (reverse-applying `jj diff --git -r <merge>` onto
 * `jj file show -r <merge>`, see [in.kkkev.jjidea.jj.JujutsuRepository.reconstructMergeParentContent]) —
 * is correct for octopus merges (5+ parents), not just the 2-parent case the plugin was originally
 * designed against.
 *
 * The reconstruction commands are issued against the merge commit itself and never reference
 * individual parents, so nothing in the command path is parent-count-aware; these tests exist to
 * verify empirically (against a real jj binary — [in.kkkev.jjidea.contract.JjStub] can't simulate
 * jj's auto-merge/conflict semantics) that 5-way auto-merge and conflict output don't produce diff
 * structure the reverse-applier mishandles.
 *
 * Notable finding from writing these tests: jj's octopus auto-merge does NOT do a clean
 * multi-way line merge the moment 3+ parents touch the same file — even on fully disjoint
 * lines, it materializes an N-way conflict for the whole file (all sides + base). So the
 * "conflicted" shape, not the "clean" one, is octopus's common case for any file more than
 * one or two parents touch; the reconstruction path is validated against both.
 */
@Tag("contract")
@RequiresJj
class OctopusMergeReconstructionTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jj: JjCli
    private lateinit var executor: CliExecutor
    private lateinit var repo: JujutsuRepository
    private lateinit var filePath: FilePath

    @BeforeEach
    fun setUp() {
        jj = JjCli(tempDir)
        jj.init()
        val root = mockk<VirtualFile> { every { path } returns tempDir.toString() }
        executor = CliExecutor(root)
        repo = mockk { every { commandExecutor } returns executor }
        filePath = mockk { every { path } returns tempDir.resolve("shared.txt").toString() }
    }

    private fun run(vararg args: String) {
        val result = jj.run(*args)
        check(result.isSuccess) { "jj ${args.joinToString(" ")} failed: ${result.stderr}" }
    }

    private fun writeShared(content: String) = tempDir.resolve("shared.txt").toFile().writeText(content)

    private fun readShared() = tempDir.resolve("shared.txt").toFile().readText()

    @Test
    fun `clean octopus merge reconstructs auto-merged parent content`() {
        writeShared("L1\nL2\nL3\nL4\nL5\n")
        run("describe", "-m", "base")
        run("bookmark", "create", "base")

        // 5 siblings off base, each adding its own disjoint file. Only p1 also edits shared.txt:
        // jj's octopus auto-merge doesn't do a clean multi-way LINE merge the moment 3+ parents
        // touch the SAME file, even on disjoint lines — it falls back to materializing an N-way
        // conflict for the whole file (see the conflicting-octopus test below, which covers that
        // shape deliberately). A single side touching the file is what stays genuinely conflict-free.
        for (i in 1..5) {
            run("new", "base", "-m", "p$i")
            if (i == 1) writeShared("L1\nL2\nL3-p1\nL4\nL5\n")
            tempDir.resolve("f$i.txt").toFile().writeText("file$i\n")
            run("bookmark", "create", "p$i")
        }

        run("new", "p1", "p2", "p3", "p4", "p5", "-m", "octopus")
        run("bookmark", "create", "octopus")

        val expectedMerged = "L1\nL2\nL3-p1\nL4\nL5\n"
        // Sanity: a single side's edit to shared.txt combines cleanly, with no conflict.
        readShared() shouldBe expectedMerged

        // The octopus commit itself now makes an additional edit on top of the auto-merge, so
        // `jj diff --git -r octopus` is non-empty and reconstruction must invert real hunks
        // rather than take the no-op "empty diff" shortcut.
        writeShared(expectedMerged.replace("L3-p1", "L3-octopus-edit"))

        val reconstructed = repo.reconstructMergeParentContent(RevisionExpression("octopus"), filePath)

        reconstructed shouldBe expectedMerged
    }

    @Test
    fun `conflicting octopus merge reconstructs materialized conflict content`() {
        writeShared("L1\nL2\nL3\nL4\nL5\n")
        run("describe", "-m", "base")
        run("bookmark", "create", "base")

        // p1 and p2 both rewrite line 1 to different values -> conflicted auto-merge.
        // p3..p5 edit disjoint lines so the rest of the octopus merge stays clean.
        for (i in 1..5) {
            run("new", "base", "-m", "p$i")
            val lines = (1..5).map { n ->
                when {
                    n == 1 && i == 1 -> "L1-sideA"
                    n == 1 && i == 2 -> "L1-sideB"
                    n == i -> "L$n-p$i"
                    else -> "L$n"
                }
            }
            writeShared(lines.joinToString("\n", postfix = "\n"))
            run("bookmark", "create", "p$i")
        }

        // Ground truth: a pure auto-merge of the same 5 parents, with no further edits on top.
        run("new", "p1", "p2", "p3", "p4", "p5", "-m", "conflict-base")
        run("bookmark", "create", "conflict-base")
        val groundTruth = readShared()
        // Sanity: line 1 is genuinely conflicted, so the ground truth contains materialized markers.
        ("<<<<<<<" in groundTruth).shouldBeTrue()

        // Same 5 parents, but this octopus commit further resolves the conflict on top of the
        // auto-merge, so reconstruction must reverse-apply a diff whose hunks add/remove lines
        // that themselves start with conflict-marker characters (+++++++, %%%%%%%, etc.).
        run("new", "p1", "p2", "p3", "p4", "p5", "-m", "octopus")
        run("bookmark", "create", "octopus")
        val resolved = Regex("(?s)<<<<<<<.*?>>>>>>>[^\n]*\n").replace(readShared(), "L1-resolved\n")
        writeShared(resolved)

        val reconstructed = repo.reconstructMergeParentContent(RevisionExpression("octopus"), filePath)

        reconstructed shouldBe groundTruth
    }
}
