package `in`.kkkev.jjidea.ui.dnd

import com.intellij.openapi.vcs.changes.Change
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RebaseDestinationMode
import `in`.kkkev.jjidea.jj.Tag
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Cell-by-cell coverage of [resolveDropOperation] against the full matrix in
 * `docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 1 - the single dispatch point
 * every gesture bead builds on, so every populated cell (and every deliberately-empty one) is
 * asserted here rather than only where a gesture bead happens to exercise it.
 */
class DropOperationDispatchTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("commit-$id"),
        underlyingDescription = "desc $id"
    )

    private val a = entry("aaaaaaaa")
    private val b = entry("bbbbbbbb")
    private val c = entry("cccccccc")

    // region Commit payload

    @Test
    fun `Commit onto CommitRow, plain, is a Rebase ONTO`() {
        val op = resolveDropOperation(DragPayload.Commit(listOf(a)), DropTarget.CommitRow(b), copy = false)

        op.shouldNotBeNull()
        op as DropOperation.Rebase
        op.sources shouldBe listOf(a)
        op.destination shouldBe b
        op.mode shouldBe RebaseDestinationMode.ONTO
    }

    @Test
    fun `Commit onto CommitRow, copy-modifier, is a Duplicate ONTO`() {
        val op = resolveDropOperation(DragPayload.Commit(listOf(a)), DropTarget.CommitRow(b), copy = true)

        op.shouldNotBeNull()
        op as DropOperation.Duplicate
        op.mode shouldBe RebaseDestinationMode.ONTO
    }

    @Test
    fun `Commit onto a Gap's top band (screen-position INSERT_BEFORE), plain, is a Rebase -A (INSERT_AFTER)`() {
        // toDestinationMode is not an identity mapping: the log renders newest-first, so the top
        // band (screen position "before") is where `jj rebase -A` (destination-mode INSERT_AFTER)
        // actually lands - see DropZone.toDestinationMode's doc.
        val op = resolveDropOperation(
            DragPayload.Commit(listOf(a)),
            DropTarget.Gap(b, DropZone.INSERT_BEFORE),
            copy = false
        )

        op.shouldNotBeNull()
        op as DropOperation.Rebase
        op.mode shouldBe RebaseDestinationMode.INSERT_AFTER
    }

    @Test
    fun `Commit onto a Gap's bottom band, copy-modifier, is a Duplicate -B (dest-mode INSERT_BEFORE)`() {
        val op = resolveDropOperation(
            DragPayload.Commit(listOf(a)),
            DropTarget.Gap(b, DropZone.INSERT_AFTER),
            copy = true
        )

        op.shouldNotBeNull()
        op as DropOperation.Duplicate
        op.mode shouldBe RebaseDestinationMode.INSERT_BEFORE
    }

    @Test
    fun `a single Commit onto a RefChip moves that bookmark to the dragged commit`() {
        val bookmark = Bookmark("main")

        val op = resolveDropOperation(DragPayload.Commit(listOf(a)), DropTarget.RefChip(b, bookmark), copy = false)

        op.shouldNotBeNull()
        op as DropOperation.MoveBookmark
        op.bookmark shouldBe bookmark
        op.destination shouldBe a
    }

    @Test
    fun `a multi-commit selection onto a RefChip is undefined - null`() {
        val op = resolveDropOperation(
            DragPayload.Commit(listOf(a, b)),
            DropTarget.RefChip(c, Bookmark("main")),
            copy = false
        )

        op.shouldBeNull()
    }

    // endregion

    // region BookmarkRef payload

    @Test
    fun `a local BookmarkRef onto a CommitRow moves the bookmark there`() {
        val bookmark = Bookmark("main")

        val op = resolveDropOperation(DragPayload.BookmarkRef(a, bookmark), DropTarget.CommitRow(b), copy = false)

        op.shouldNotBeNull()
        op as DropOperation.MoveBookmark
        op.bookmark shouldBe bookmark
        op.destination shouldBe b
    }

    @Test
    fun `a local BookmarkRef onto its remote RefChip is a Push`() {
        val local = Bookmark("main")
        val remote = Bookmark("main@origin")

        val op = resolveDropOperation(DragPayload.BookmarkRef(a, local), DropTarget.RefChip(b, remote), copy = false)

        op.shouldNotBeNull()
        op as DropOperation.Push
        op.bookmark shouldBe local
        op.remote shouldBe "origin"
    }

    @Test
    fun `a remote BookmarkRef onto a local RefChip is not a push - null`() {
        val remote = Bookmark("main@origin")
        val local = Bookmark("main")

        resolveDropOperation(DragPayload.BookmarkRef(a, remote), DropTarget.RefChip(b, local), copy = false)
            .shouldBeNull()
    }

    @Test
    fun `a BookmarkRef onto a Gap has no operation - null`() {
        resolveDropOperation(
            DragPayload.BookmarkRef(a, Bookmark("main")),
            DropTarget.Gap(b, DropZone.INSERT_BEFORE),
            copy = false
        )
            .shouldBeNull()
    }

    // endregion

    // region TagRef, WorkingCopyRef

    @Test
    fun `a TagRef onto a CommitRow moves the tag there`() {
        val tag = Tag("v1")

        val op = resolveDropOperation(DragPayload.TagRef(a, tag), DropTarget.CommitRow(b), copy = false)

        op.shouldNotBeNull()
        op as DropOperation.MoveTag
        op.tag shouldBe tag
        op.destination shouldBe b
    }

    @Test
    fun `a TagRef onto a Gap has no operation - null`() {
        resolveDropOperation(DragPayload.TagRef(a, Tag("v1")), DropTarget.Gap(b, DropZone.INSERT_AFTER), copy = false)
            .shouldBeNull()
    }

    @Test
    fun `WorkingCopyRef onto a CommitRow edits that commit`() {
        val op = resolveDropOperation(DragPayload.WorkingCopyRef(a), DropTarget.CommitRow(b), copy = false)

        op.shouldNotBeNull()
        op as DropOperation.EditWorkingCopy
        op.destination shouldBe b
    }

    @Test
    fun `WorkingCopyRef onto a Gap has no operation - null`() {
        resolveDropOperation(DragPayload.WorkingCopyRef(a), DropTarget.Gap(b, DropZone.INSERT_BEFORE), copy = false)
            .shouldBeNull()
    }

    // endregion

    // region Files

    @Test
    fun `Files dropped on a CommitRow centre squashes into that commit`() {
        val files = DragPayload.Files(a, listOf(mockk<Change>()))

        val op = resolveDropOperation(files, DropTarget.CommitRow(b), copy = false)

        op.shouldNotBeNull()
        op as DropOperation.SquashFiles
        op.destination shouldBe b
    }

    @Test
    fun `Files dropped in a gap bordering their own change splits them out`() {
        val files = DragPayload.Files(a, listOf(mockk<Change>()))
        val gap = DropTarget.Gap(a, DropZone.INSERT_AFTER)

        val op = resolveDropOperation(files, gap, copy = false)

        op.shouldNotBeNull()
        op as DropOperation.SplitFiles
        op.gap shouldBe gap
    }

    @Test
    fun `Files dropped in a gap NOT bordering their own change is rejected - null`() {
        val files = DragPayload.Files(a, listOf(mockk<Change>()))

        resolveDropOperation(files, DropTarget.Gap(b, DropZone.INSERT_AFTER), copy = false).shouldBeNull()
    }

    @Test
    fun `Files dropped on a RefChip has no operation - null`() {
        val files = DragPayload.Files(a, listOf(mockk<Change>()))

        resolveDropOperation(files, DropTarget.RefChip(b, Bookmark("main")), copy = false).shouldBeNull()
    }

    // endregion
}
