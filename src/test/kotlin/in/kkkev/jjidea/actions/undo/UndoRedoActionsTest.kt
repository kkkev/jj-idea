package `in`.kkkev.jjidea.actions.undo

import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult.Success.Irreversible.Reason.READ_ONLY
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.OperationId
import `in`.kkkev.jjidea.jj.UNDO_REDO_WINDOW
import `in`.kkkev.jjidea.jj.UndoRedoPlans
import `in`.kkkev.jjidea.jj.cli.UndoRedoPlan
import `in`.kkkev.jjidea.jj.loadUndoRedoPlans
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private const val NUL = "\u0000"

/**
 * Tests for the pure logic behind [UndoAction] / [RedoAction] (jj-idea-x30x): text, enablement and
 * reason, repository choice, label shortening, confirmation text, and the operation count of
 * loading the plans. Per contributing.md's action-availability-hints tenet every unavailable case
 * is disabled with a reason, never hidden.
 */
class UndoRedoActionsTest {
    private val repo = mockk<JujutsuRepository>()
    private val other = mockk<JujutsuRepository>()

    private fun op(id: String, description: String, snapshot: Boolean = false, parent: String? = null) =
        OperationEntry(
            id = OperationId(id),
            parents = listOfNotNull(parent).map(::OperationId),
            description = description,
            time = Instant.fromEpochSeconds(1_788_000_000),
            user = "kevin@host",
            isSnapshot = snapshot,
            isRoot = false,
            args = ""
        )

    private fun named(description: String) = UndoRedoPlan.Named(op("b", description), OperationId("a"))

    // --- presentation ---

    @Test
    fun `a named undo reads as Undo plus the operation`() {
        val presentation = resolveUndoRedoPresentation(
            UndoRedoKind.UNDO,
            RepoChoice.Chosen(repo),
            named("new empty commit")
        )

        presentation.text shouldBe "Undo 'new empty commit'"
        presentation.enabled shouldBe true
    }

    @Test
    fun `a named redo reads as Redo plus the operation`() {
        resolveUndoRedoPresentation(UndoRedoKind.REDO, RepoChoice.Chosen(repo), named("rebase 3 commits")).text shouldBe
            "Redo 'rebase 3 commits'"
    }

    @Test
    fun `an unnameable operation falls back to a generic enabled label, never a wrong name`() {
        val undo = resolveUndoRedoPresentation(UndoRedoKind.UNDO, RepoChoice.Chosen(repo), UndoRedoPlan.Unnamed)
        val redo = resolveUndoRedoPresentation(UndoRedoKind.REDO, RepoChoice.Chosen(repo), UndoRedoPlan.Unnamed)

        undo.text shouldBe "Undo Last Operation"
        undo.enabled shouldBe true
        redo.text shouldBe "Redo Last Undone Operation"
        redo.enabled shouldBe true
    }

    @Test
    fun `nothing to undo or redo is disabled with a reason`() {
        val plan = UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)

        resolveUndoRedoPresentation(UndoRedoKind.UNDO, RepoChoice.Chosen(repo), plan).let {
            it.enabled shouldBe false
            it.description shouldBe "Nothing to undo"
        }
        resolveUndoRedoPresentation(UndoRedoKind.REDO, RepoChoice.Chosen(repo), plan).let {
            it.enabled shouldBe false
            it.description shouldBe "Nothing to redo"
        }
    }

    @Test
    fun `a snapshot head is undoable but labelled as working-copy changes, never as the op beneath it`() {
        val snapshot = UndoRedoPlan.Named(op("s", "snapshot working copy", snapshot = true), OperationId("a"))

        val undo = resolveUndoRedoPresentation(UndoRedoKind.UNDO, RepoChoice.Chosen(repo), snapshot)

        undo.enabled shouldBe true
        undo.text shouldBe "Undo Working-Copy Changes"
        undo.description!! shouldContain "Redo brings them back"
        undoRedoConfirmMessage(UndoRedoKind.UNDO, snapshot) shouldContain "removed from disk"
    }

    @Test
    fun `the vcs mapping warning names the file and offers both recoveries`() {
        val message = vcsMappingRiskMessage(listOf("proj/.idea/vcs.xml"))

        message shouldContain "proj/.idea/vcs.xml"
        message shouldContain "switch itself off"
        message shouldContain "jj redo"
        message shouldContain "Settings > Version Control > Directories"
    }

    @Test
    fun `only files that exist now and would be gone after restoring count as removed`() {
        removedByRestore("A proj/.idea/vcs.xml\nM other.xml\nD gone.txt\n") shouldBe listOf("proj/.idea/vcs.xml")
        removedByRestore("") shouldBe emptyList()
    }

    @Test
    fun `redo is unavailable under a snapshot because it ends the redo chain`() {
        val plan = UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.WORKING_COPY_CHANGED)

        val redo = resolveUndoRedoPresentation(UndoRedoKind.REDO, RepoChoice.Chosen(repo), plan)

        redo.enabled shouldBe false
        redo.description!! shouldContain "ends the redo chain"
    }

    @Test
    fun `no repository or an ambiguous one is disabled, and a not-yet-loaded log says so`() {
        resolveUndoRedoPresentation(UndoRedoKind.UNDO, RepoChoice.None, null).let {
            it.enabled shouldBe false
            it.description shouldBe "Select a repository to undo in"
        }
        resolveUndoRedoPresentation(UndoRedoKind.REDO, RepoChoice.Ambiguous, named("x")).enabled shouldBe false
        resolveUndoRedoPresentation(UndoRedoKind.UNDO, RepoChoice.Chosen(repo), null).description shouldBe
            "Loading the operation log..."
    }

    // --- repository choice ---

    @Test
    fun `the context repository wins, then the only repository, else none or ambiguous`() {
        chooseRepo(repo, listOf(repo, other)) shouldBe RepoChoice.Chosen(repo)
        chooseRepo(null, listOf(other)) shouldBe RepoChoice.Chosen(other)
        chooseRepo(null, emptyList()) shouldBe RepoChoice.None
        chooseRepo(null, listOf(repo, other)) shouldBe RepoChoice.Ambiguous
    }

    // --- label ---

    @Test
    fun `commit hashes in an operation description are shortened`() {
        operationLabel(op("x", "describe commit 19600818fb62d1395746972771cdfa3a80096bfa")) shouldBe
            "describe commit 19600818"
    }

    @Test
    fun `a very long description is bounded with an ellipsis`() {
        val label = operationLabel(op("x", "squash " + "word ".repeat(40)))

        label.length shouldBe 60
        label.last() shouldBe '…'
    }

    @Test
    fun `an operation with no description is labelled by the placeholder, not blank`() {
        operationLabel(op("x", "")) shouldBe "(no description)"
    }

    // --- confirmation ---

    @Test
    fun `the confirmation names the operation, its time and the consequence`() {
        val message = undoRedoConfirmMessage(UndoRedoKind.UNDO, named("rebase 3 commits"))

        message shouldContain "rebase 3 commits"
        message shouldContain "files on disk"
        message shouldContain "terminal"
    }

    @Test
    fun `an unnamed confirmation does not invent a name`() {
        undoRedoConfirmMessage(UndoRedoKind.UNDO, UndoRedoPlan.Unnamed) shouldNotContain "''"
        undoRedoConfirmMessage(UndoRedoKind.REDO, UndoRedoPlan.Unnamed) shouldContain "cannot be named"
    }

    // --- loading (operation count, contributing.md § Performance & Scale) ---

    private fun record(id: String, description: String, parent: String) =
        listOf(id, parent, description, "1788000000", "kevin", "0", "0", "args").joinToString(NUL, postfix = NUL)

    private fun ok(stdout: String) = CommandExecutor.CommandResult.Success.Irreversible(stdout, "", READ_ONLY)

    @Test
    fun `loading both plans costs exactly one bounded op log read, whatever the history length`() {
        val executor = mockk<CommandExecutor>()
        val history = (1..UNDO_REDO_WINDOW).joinToString("") { record("op$it", "describe commit $it", "op${it + 1}") }
        every { executor.opLog(any(), any()) } returns ok(history)

        val plans = loadUndoRedoPlans(executor)

        verify(exactly = 1) { executor.opLog(UNDO_REDO_WINDOW, any()) }
        plans.undo.shouldBeInstanceOf<UndoRedoPlan.Named>()
        plans.redo shouldBe UndoRedoPlan.Unavailable(UndoRedoPlan.Reason.NOTHING)
    }

    @Test
    fun `a failed op log read degrades to disabled actions, not a guess`() {
        val executor = mockk<CommandExecutor>()
        every { executor.opLog(any(), any()) } returns
            CommandExecutor.CommandResult.Failure.Exited("", "boom", 1)

        loadUndoRedoPlans(executor) shouldBe UndoRedoPlans.NOTHING
    }
}
