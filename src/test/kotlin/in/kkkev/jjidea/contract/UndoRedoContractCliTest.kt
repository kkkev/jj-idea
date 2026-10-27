package `in`.kkkev.jjidea.contract

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.actions.undo.VCS_MAPPING_FILESET
import `in`.kkkev.jjidea.actions.undo.removedByRestore
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.jj.cli.OP_LOG_TEMPLATE
import `in`.kkkev.jjidea.jj.cli.UndoRedoOutcome
import `in`.kkkev.jjidea.jj.cli.UndoRedoPlan
import `in`.kkkev.jjidea.jj.cli.classifyUndoRedoOutcome
import `in`.kkkev.jjidea.jj.cli.parseOpLog
import `in`.kkkev.jjidea.jj.cli.resolveRedo
import `in`.kkkev.jjidea.jj.cli.resolveUndo
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
 * jj-idea-x30x: the prediction made by [resolveUndo]/[resolveRedo] from the op log alone must match
 * what real `jj undo`/`jj redo` do. The check is semantic, not textual: after each step the
 * repository state must equal the state at the operation the plan said jj would restore to, so a
 * wrong rule in the resolver fails here even if it still "looks" plausible. CLI-only (JjStub has
 * no op log); run against other versions with a PATH shim as in [CommitIdReadsContractCliTest].
 */
@Tag("contract")
@RequiresJj
class UndoRedoContractCliTest {
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

    private fun entries(): List<OperationEntry> {
        val result = executor.opLog(50, OP_LOG_TEMPLATE).shouldBeInstanceOf<CommandResult.Success>()
        return parseOpLog(result.stdout)
    }

    /** Change ids + first description lines + working-copy marker, at [atOperation] or now. */
    private fun repoState(atOperation: String? = null): String {
        val args = buildList {
            add("log")
            add("--ignore-working-copy")
            if (atOperation != null) {
                add("--at-operation")
                add(atOperation)
            }
            addAll(
                listOf(
                    "-r",
                    "all()",
                    "--no-graph",
                    "-T",
                    "change_id ++ \" \" ++ description.first_line() ++ \" wc=\" ++ " +
                        "if(current_working_copy, \"1\", \"0\") ++ \"\\n\""
                )
            )
        }
        return jj.run(*args.toTypedArray()).stdout
    }

    private fun undoStep(): UndoRedoPlan.Named {
        val plan = resolveUndo(entries()).shouldBeInstanceOf<UndoRedoPlan.Named>()
        val expected = repoState(plan.restoresTo.toString())
        executor.undo().shouldBeInstanceOf<CommandResult.Success>()
        repoState() shouldBe expected
        return plan
    }

    private fun redoStep(): UndoRedoPlan.Named {
        val plan = resolveRedo(entries()).shouldBeInstanceOf<UndoRedoPlan.Named>()
        val expected = repoState(plan.restoresTo.toString())
        executor.redo().shouldBeInstanceOf<CommandResult.Success>()
        repoState() shouldBe expected
        return plan
    }

    private fun twoOperations() {
        jj.describe("A")
        jj.newChange("B")
    }

    @Test
    fun `A, B, undo, undo, redo, redo - every step is predicted and named correctly`() {
        twoOperations()
        val afterB = repoState()

        // undo #1 names B's op ("new empty commit"), undo #2 names A's ("describe commit ...").
        undoStep().labelOp.description shouldContain "new empty commit"
        undoStep().labelOp.description shouldContain "describe commit"

        // Redo reinstates in the opposite order: A first, then B.
        redoStep().labelOp.description shouldContain "describe commit"
        redoStep().labelOp.description shouldContain "new empty commit"

        repoState() shouldBe afterB
        resolveRedo(entries()) shouldBe UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
        executor.redo().shouldBeInstanceOf<CommandResult.Failure.Exited>()
    }

    @Test
    fun `undo after a redo names what the redo reinstated`() {
        twoOperations()
        undoStep()
        undoStep()
        redoStep() // reinstates A

        undoStep().labelOp.description shouldContain "describe commit"
    }

    @Test
    fun `a new operation after an undo ends the redo chain`() {
        twoOperations()
        undoStep()
        jj.newChange("C")

        resolveRedo(entries()) shouldBe UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
        executor.redo().shouldBeInstanceOf<CommandResult.Failure.Exited>()
        // ...and undo now targets C, not the earlier undo's subject.
        undoStep().labelOp.description shouldContain "new empty commit"
    }

    @Test
    fun `an ambient snapshot at the head is undone, then redone, with the files coming back`() {
        twoOperations()
        jj.createFile("ambient.txt", "written by the IDE")
        executor.status().shouldBeInstanceOf<CommandResult.Success>() // any command snapshots first
        val file = tempDir.resolve("ambient.txt")
        val withSnapshot = repoState()

        // Undo names the snapshot itself and removes its files; further undos then walk to the real ops.
        undoStep().labelOp.isSnapshot shouldBe true
        file.toFile().exists() shouldBe false
        executor.status().shouldBeInstanceOf<CommandResult.Success>() // nothing left to re-snapshot
        undoStep().labelOp.description shouldContain "new empty commit"

        // Redo walks forward again and finally brings the file back.
        redoStep().labelOp.description shouldContain "new empty commit"
        redoStep().labelOp.isSnapshot shouldBe true
        file.toFile().readText() shouldBe "written by the IDE"
        repoState() shouldBe withSnapshot
    }

    @Test
    fun `redo under a snapshot is unavailable in jj as well as in the plan`() {
        twoOperations()
        undoStep()
        jj.createFile("ambient.txt", "x")
        executor.status().shouldBeInstanceOf<CommandResult.Success>()

        resolveRedo(entries()) shouldBe UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.WORKING_COPY_CHANGED)
        executor.redo().shouldBeInstanceOf<CommandResult.Failure.Exited>()
    }

    @Test
    fun `the vcs mapping check fires only when the undo would really remove vcs xml`() {
        twoOperations()
        jj.createFile("sub/proj/.idea/vcs.xml", "<project/>")
        executor.status().shouldBeInstanceOf<CommandResult.Success>()
        // Undoing this snapshot removes vcs.xml, wherever the project sits in the repository.
        val first = resolveUndo(entries()).shouldBeInstanceOf<UndoRedoPlan.Named>()
        val hit = executor.filesChangedByRestoring(first.restoresTo, VCS_MAPPING_FILESET)
        removedByRestore(hit.shouldBeInstanceOf<CommandResult.Success>().stdout) shouldBe
            listOf("sub/proj/.idea/vcs.xml")

        // A later snapshot that records only other files must not trigger the warning.
        jj.createFile("sub/proj/.idea/workspace.xml", "<project/>")
        executor.status().shouldBeInstanceOf<CommandResult.Success>()
        val second = resolveUndo(entries()).shouldBeInstanceOf<UndoRedoPlan.Named>()
        val miss = executor.filesChangedByRestoring(second.restoresTo, VCS_MAPPING_FILESET)
        miss.shouldBeInstanceOf<CommandResult.Success>().stdout.isBlank() shouldBe true
    }

    @Test
    fun `an ordinary operation that leaves vcs xml alone does not trigger it either`() {
        jj.createFile("proj/.idea/vcs.xml", "<project/>")
        jj.describe("A")
        jj.newChange("B")
        val plan = resolveUndo(entries()).shouldBeInstanceOf<UndoRedoPlan.Named>()

        executor.filesChangedByRestoring(plan.restoresTo, VCS_MAPPING_FILESET)
            .shouldBeInstanceOf<CommandResult.Success>().stdout.isBlank() shouldBe true
    }

    @Test
    fun `outcome classification distinguishes a clean undo from one that only undid a snapshot`() {
        twoOperations()
        val cleanHead = entries().first().id
        executor.undo().shouldBeInstanceOf<CommandResult.Success>()
        classifyUndoRedoOutcome(entries(), cleanHead) shouldBe UndoRedoOutcome.DONE

        // Edits made after the plan was resolved: jj snapshots, then undoes only the snapshot.
        val dirtyHead = entries().first().id
        jj.createFile("dirty.txt", "uncommitted edit")
        executor.undo().shouldBeInstanceOf<CommandResult.Success>()
        classifyUndoRedoOutcome(entries(), dirtyHead) shouldBe UndoRedoOutcome.SNAPSHOT_ONLY
    }
}
