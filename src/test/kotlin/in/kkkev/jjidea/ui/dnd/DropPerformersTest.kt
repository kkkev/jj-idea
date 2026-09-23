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
import org.junit.jupiter.api.Test

/**
 * jj-idea-8fxs, -p6nb, -ibth, -vdwh, -yvry, -b2oi, -pk2c, -d3u5: [DropPerformers.forLogTable]'s
 * [DropOperation.Rebase]/[DropOperation.Duplicate]/[DropOperation.MoveBookmark]/
 * [DropOperation.MoveTag]/[DropOperation.Push]/[DropOperation.SquashFiles]/
 * [DropOperation.SplitFiles]/[DropOperation.EditWorkingCopy]/[DropOperation.NewChangeOnTop]
 * mappings, and the [DropPerformer.supports]/[DropPerformer.perform] contract every gesture bead
 * extends. `executeRebase`/`executeDuplicate`/`executeMove`/`executeSetTag`/`openPushDialogFor`/
 * `performFileSquashInto`/`performFileSplit`/`editWorkingCopy`/`newChangeOnTop`'s own chains (undo
 * tracking, the balloon, `executeAsync`, `runInBackground`, opening the pre-filled dialog) are
 * manual-verified - see each bead's design TESTS section - so `perform` is never actually invoked
 * here (it would need a live `ApplicationManager`, which a plain unit test doesn't have); this
 * only covers the pure mapping functions and that every operation reports itself supported.
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
        DropOperation.EditWorkingCopy(b),
        DropOperation.NewChangeOnTop(b),
        DropOperation.Push(Bookmark("main"), "origin", b.repo),
        DropOperation.SquashFiles(DragPayload.Files(a, listOf(mockk())), b),
        DropOperation.SplitFiles(DragPayload.Files(a, listOf(mockk())), DropTarget.Gap(a, DropZone.INSERT_AFTER))
    )

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
    fun `toRebaseSpec defaults to REVISION source mode - must match DragContext's cycle-exclusion assumption`() {
        val op = DropOperation.Rebase(listOf(a), b, RebaseDestinationMode.ONTO)

        op.toRebaseSpec().sourceMode shouldBe RebaseSourceMode.REVISION
    }

    @Test
    fun `toRebaseSpec carries the operation's own source mode through - jj-idea-j8ij`() {
        val op = DropOperation.Rebase(listOf(a), b, RebaseDestinationMode.ONTO, sourceMode = RebaseSourceMode.SOURCE)

        op.toRebaseSpec().sourceMode shouldBe RebaseSourceMode.SOURCE
    }

    @Test
    fun `toRebaseSpec never pre-expands revisions for -s or -b - jj rebase computes that server-side`() {
        val op = DropOperation.Rebase(
            listOf(a),
            b,
            RebaseDestinationMode.ONTO,
            sourceMode = RebaseSourceMode.BRANCH,
            movedCount = 5
        )

        op.toRebaseSpec().revisions shouldBe listOf(a.id)
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

    // region toNewParent (jj-idea-b2oi)

    @Test
    fun `toNewParent maps the bottom band (INSERT_AFTER) to newParent = true - the split -B parent-side slot`() {
        val op = DropOperation.SplitFiles(
            DragPayload.Files(a, listOf(mockk())),
            DropTarget.Gap(a, DropZone.INSERT_AFTER)
        )

        op.toNewParent() shouldBe true
    }

    @Test
    fun `toNewParent maps the top band (INSERT_BEFORE) to newParent = false - the default new-child slot`() {
        val op = DropOperation.SplitFiles(
            DragPayload.Files(a, listOf(mockk())),
            DropTarget.Gap(a, DropZone.INSERT_BEFORE)
        )

        op.toNewParent() shouldBe false
    }

    // endregion

    // region Push (jj-idea-vdwh, -3xab)

    @Test
    fun `Push carries repo directly - no LogEntry needed, the off-window bookmark case (jj-idea-3xab)`() {
        val op = DropOperation.Push(Bookmark("main"), "origin", repo)

        op.repo shouldBe repo
    }

    // endregion

    // region supports / perform dispatch

    @Test
    fun `supports is true for every DropOperation variant - none left unwired`() {
        val performer = DropPerformers.forLogTable(project)

        wiredOperations.forEach { operation ->
            performer.supports(operation) shouldBe true
        }
    }

    // endregion
}
