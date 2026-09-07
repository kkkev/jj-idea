package `in`.kkkev.jjidea.ui.dnd

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RebaseDestinationMode
import `in`.kkkev.jjidea.jj.RebaseSourceMode
import `in`.kkkev.jjidea.jj.Tag
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * jj-idea-8fxs, -p6nb, -ibth, -vdwh: [DropPerformers.forLogTable]'s
 * [DropOperation.Rebase]/[DropOperation.Duplicate]/[DropOperation.MoveBookmark]/
 * [DropOperation.MoveTag]/[DropOperation.Push] mappings, and the
 * [DropPerformer.supports]/[DropPerformer.perform] contract every gesture bead extends.
 * `executeRebase`/`executeDuplicate`/`executeMove`/`executeSetTag`/`openPushDialogFor`'s own
 * chains (undo tracking, the balloon, `executeAsync`, `runInBackground`) are manual-verified - see
 * each bead's design TESTS section - so `perform` is never actually invoked here for a wired
 * operation (it would need a live `ApplicationManager`, which a plain unit test doesn't have);
 * this only covers the pure mapping functions and the dispatch contract for the still-unwired
 * cells.
 */
class DropPerformersTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)

    private fun entry(id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("commit-$id"),
        underlyingDescription = "desc $id"
    )

    private val a = entry("aaaaaaaa")
    private val b = entry("bbbbbbbb")
    private val c = entry("cccccccc")

    private val wiredOperations: List<DropOperation> = listOf(
        DropOperation.Rebase(listOf(a), b, RebaseDestinationMode.ONTO),
        DropOperation.Duplicate(listOf(a), b, RebaseDestinationMode.ONTO),
        DropOperation.MoveBookmark(Bookmark("main"), b),
        DropOperation.MoveTag(Tag("v1"), b),
        DropOperation.Push(Bookmark("main"), "origin", b)
    )

    private val unwiredOperations: List<DropOperation> = listOf(
        DropOperation.EditWorkingCopy(b),
        DropOperation.SquashFiles(DragPayload.Files(a, listOf(mockk())), b),
        DropOperation.SplitFiles(DragPayload.Files(a, listOf(mockk())), DropTarget.Gap(a, DropZone.INSERT_AFTER))
    )

    private val allOperations: List<DropOperation> = wiredOperations + unwiredOperations

    // region toRebaseSpec

    @Test
    fun `toRebaseSpec maps ONTO`() {
        val op = DropOperation.Rebase(listOf(a), b, RebaseDestinationMode.ONTO)

        val spec = op.toRebaseSpec()

        spec.revisions shouldBe listOf(a.id)
        spec.destinations shouldBe listOf(b.id)
        spec.sourceMode shouldBe RebaseSourceMode.REVISION
        spec.destinationMode shouldBe RebaseDestinationMode.ONTO
    }

    @Test
    fun `toRebaseSpec maps INSERT_BEFORE`() {
        val op = DropOperation.Rebase(listOf(a), b, RebaseDestinationMode.INSERT_BEFORE)

        op.toRebaseSpec().destinationMode shouldBe RebaseDestinationMode.INSERT_BEFORE
    }

    @Test
    fun `toRebaseSpec maps INSERT_AFTER`() {
        val op = DropOperation.Rebase(listOf(a), b, RebaseDestinationMode.INSERT_AFTER)

        op.toRebaseSpec().destinationMode shouldBe RebaseDestinationMode.INSERT_AFTER
    }

    @Test
    fun `toRebaseSpec carries a multi-source drag through in order`() {
        val op = DropOperation.Rebase(listOf(a, c), b, RebaseDestinationMode.ONTO)

        op.toRebaseSpec().revisions shouldBe listOf(a.id, c.id)
    }

    @Test
    fun `toRebaseSpec always uses REVISION source mode - must match DragContext's cycle-exclusion assumption`() {
        val op = DropOperation.Rebase(listOf(a), b, RebaseDestinationMode.ONTO)

        op.toRebaseSpec().sourceMode shouldBe RebaseSourceMode.REVISION
    }

    // endregion

    // region toDuplicateSpec

    @Test
    fun `toDuplicateSpec maps ONTO`() {
        val op = DropOperation.Duplicate(listOf(a), b, RebaseDestinationMode.ONTO)

        val spec = op.toDuplicateSpec()

        spec.revisions shouldBe listOf(a.id)
        spec.destinations shouldBe listOf(b.id)
        spec.mode shouldBe RebaseDestinationMode.ONTO
    }

    @Test
    fun `toDuplicateSpec maps INSERT_BEFORE`() {
        val op = DropOperation.Duplicate(listOf(a), b, RebaseDestinationMode.INSERT_BEFORE)

        op.toDuplicateSpec().mode shouldBe RebaseDestinationMode.INSERT_BEFORE
    }

    @Test
    fun `toDuplicateSpec maps INSERT_AFTER`() {
        val op = DropOperation.Duplicate(listOf(a), b, RebaseDestinationMode.INSERT_AFTER)

        op.toDuplicateSpec().mode shouldBe RebaseDestinationMode.INSERT_AFTER
    }

    @Test
    fun `toDuplicateSpec carries a multi-source drag through in order`() {
        val op = DropOperation.Duplicate(listOf(a, c), b, RebaseDestinationMode.ONTO)

        op.toDuplicateSpec().revisions shouldBe listOf(a.id, c.id)
    }

    // endregion

    // region supports / perform dispatch

    @Test
    fun `supports is true exactly for the wired operations`() {
        val performer = DropPerformers.forLogTable(project)

        allOperations.forEach { operation ->
            performer.supports(operation) shouldBe (operation in wiredOperations)
        }
    }

    @Test
    fun `perform on an unwired operation returns false and never touches the executor`() {
        val performer = DropPerformers.forLogTable(project)

        unwiredOperations.forEach { operation ->
            performer.perform(operation) shouldBe false
        }
        verify(exactly = 0) { repo.commandExecutor }
    }

    @Test
    fun `supports and perform agree on the unwired operations`() {
        val performer = DropPerformers.forLogTable(project)

        unwiredOperations.forEach { operation ->
            performer.supports(operation) shouldBe false
            performer.perform(operation) shouldBe false
        }
    }

    // endregion
}
