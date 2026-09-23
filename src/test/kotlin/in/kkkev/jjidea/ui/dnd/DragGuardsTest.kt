package `in`.kkkev.jjidea.ui.dnd

import com.intellij.openapi.vcs.changes.Change
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RebaseSourceMode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Coverage for [DragContext] (design section 6's guard list: immutability, cycles,
 * cross-repository drops, self-drop).
 */
class DragGuardsTest {
    private val repoA = mockk<JujutsuRepository>(relaxed = true)
    private val repoB = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(
        id: String,
        repo: JujutsuRepository = repoA,
        immutable: Boolean = false,
        parentIds: List<ChangeId> = emptyList()
    ) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("commit-$id"),
        underlyingDescription = "desc $id",
        immutable = immutable,
        parentIds = parentIds
    )

    @Test
    fun `a drop into a different repository is rejected with an explanatory reason`() {
        val source = entry("aaaaaaaa", repo = repoA)
        val destInOtherRepo = entry("bbbbbbbb", repo = repoB)
        val context = DragContext.forDrag(listOf(source), DragPayload.Commit(listOf(source)))

        val reason = context.rejectionReason(DropTarget.CommitRow(destInOtherRepo), copy = false)

        reason shouldBe "Cannot drop across repositories"
    }

    @Test
    fun `dropping a commit onto itself is rejected silently - empty string, not a message`() {
        val a = entry("aaaaaaaa")
        val context = DragContext.forDrag(listOf(a), DragPayload.Commit(listOf(a)))

        // REVISION source mode's own excludedDestinationIds is exactly the source set, so the
        // self-drop case is what "cycle" reduces to for a plain `-r` drag: jj explicitly allows
        // rebasing a revision onto its own descendant (RebaseSimulator's own doc on REVISION
        // mode), so only the source itself is ever an invalid destination here.
        val reason = context.rejectionReason(DropTarget.CommitRow(a), copy = false)

        reason shouldBe ""
    }

    @Test
    fun `dropping one member of a multi-commit selection onto another member is also a silent self-drop`() {
        val a = entry("aaaaaaaa")
        val b = entry("bbbbbbbb")
        val context = DragContext.forDrag(listOf(a, b), DragPayload.Commit(listOf(a, b)))

        context.rejectionReason(DropTarget.CommitRow(b), copy = false) shouldBe ""
    }

    @Test
    fun `an immutable commit cannot be the source of a plain rebase`() {
        val immutableSource = entry("aaaaaaaa", immutable = true)
        val dest = entry("bbbbbbbb")
        val context = DragContext.forDrag(
            listOf(immutableSource, dest),
            DragPayload.Commit(listOf(immutableSource))
        )

        val reason = context.rejectionReason(DropTarget.CommitRow(dest), copy = false)

        reason shouldBe "Cannot rewrite an immutable commit"
    }

    @Test
    fun `an immutable source is fine for a copy-modifier drag - duplicate never rewrites the source`() {
        val immutableSource = entry("aaaaaaaa", immutable = true)
        val dest = entry("bbbbbbbb")
        val context = DragContext.forDrag(
            listOf(immutableSource, dest),
            DragPayload.Commit(listOf(immutableSource))
        )

        context.rejectionReason(DropTarget.CommitRow(dest), copy = true).shouldBeNull()
    }

    @Test
    fun `bottom band onto an immutable destination is rejected - dest-mode INSERT_BEFORE rewrites it directly`() {
        // Bottom band (screen INSERT_AFTER) -> destination-mode INSERT_BEFORE, per
        // DropZone.toDestinationMode - the one that rewrites the destination itself.
        val source = entry("aaaaaaaa")
        val immutableDest = entry("bbbbbbbb", immutable = true)
        val context = DragContext.forDrag(listOf(source, immutableDest), DragPayload.Commit(listOf(source)))

        val reason = context.rejectionReason(DropTarget.Gap(immutableDest, DropZone.INSERT_AFTER), copy = false)

        reason shouldBe "bbbbbbbb is immutable"
    }

    @Test
    fun `top band onto a destination with an immutable child is rejected - dest-mode INSERT_AFTER reparents it`() {
        // Top band (screen INSERT_BEFORE) -> destination-mode INSERT_AFTER, per
        // DropZone.toDestinationMode - the one that reparents the destination's children.
        val source = entry("aaaaaaaa")
        val dest = entry("bbbbbbbb")
        val immutableChild = entry("cccccccc", immutable = true, parentIds = listOf(dest.id))
        val entries = listOf(source, dest, immutableChild)
        val context = DragContext.forDrag(entries, DragPayload.Commit(listOf(source)))

        val reason = context.rejectionReason(DropTarget.Gap(dest, DropZone.INSERT_BEFORE), copy = false)

        reason shouldBe "bbbbbbbb is immutable"
    }

    @Test
    fun `ONTO never rejects for immutability - the destination isn't rewritten`() {
        val source = entry("aaaaaaaa")
        val immutableDest = entry("bbbbbbbb", immutable = true)
        val context = DragContext.forDrag(listOf(source, immutableDest), DragPayload.Commit(listOf(source)))

        context.rejectionReason(DropTarget.CommitRow(immutableDest), copy = false).shouldBeNull()
    }

    @Test
    fun `a RefChip target is exempt from the source-immutability check - moving a bookmark never rewrites content`() {
        val immutableSource = entry("aaaaaaaa", immutable = true)
        val dest = entry("bbbbbbbb")
        val context = DragContext.forDrag(
            listOf(immutableSource, dest),
            DragPayload.Commit(listOf(immutableSource))
        )

        context.rejectionReason(DropTarget.RefChip(dest.repo, dest.id, Bookmark("main")), copy = false).shouldBeNull()
    }

    @Test
    fun `a non-Commit payload (bookmark ref) is never subject to the cycle or source-immutability checks`() {
        val a = entry("aaaaaaaa", immutable = true)
        val b = entry("bbbbbbbb")
        val context = DragContext.forDrag(listOf(a, b), DragPayload.BookmarkRef(a.repo, a.id, Bookmark("main")))

        context.rejectionReason(DropTarget.CommitRow(b), copy = false).shouldBeNull()
    }

    // region Rebase source mode (jj-idea-j8ij)

    @Test
    fun `SOURCE scope rejects an immutable descendant even though the dragged entry itself is mutable`() {
        val source = entry("aaaaaaaa")
        val immutableChild = entry("bbbbbbbb", immutable = true, parentIds = listOf(source.id))
        val dest = entry("cccccccc")
        val entries = listOf(source, immutableChild, dest)
        val context = DragContext.forDrag(entries, DragPayload.Commit(listOf(source)), RebaseSourceMode.SOURCE)

        val reason = context.rejectionReason(DropTarget.CommitRow(dest), copy = false)

        reason shouldBe "Cannot rewrite an immutable commit"
    }

    @Test
    fun `REVISION scope (the default) doesn't see that same immutable descendant - only -s or -b do`() {
        val source = entry("aaaaaaaa")
        val immutableChild = entry("bbbbbbbb", immutable = true, parentIds = listOf(source.id))
        val dest = entry("cccccccc")
        val entries = listOf(source, immutableChild, dest)
        val context = DragContext.forDrag(entries, DragPayload.Commit(listOf(source)), RebaseSourceMode.REVISION)

        context.rejectionReason(DropTarget.CommitRow(dest), copy = false).shouldBeNull()
    }

    @Test
    fun `BRANCH scope rejects when an ancestor elsewhere in the branch is immutable`() {
        val root = entry("aaaaaaaa", immutable = true)
        val mid = entry("bbbbbbbb", parentIds = listOf(root.id))
        val dest = entry("cccccccc")
        val entries = listOf(root, mid, dest)
        val context = DragContext.forDrag(entries, DragPayload.Commit(listOf(mid)), RebaseSourceMode.BRANCH)

        val reason = context.rejectionReason(DropTarget.CommitRow(dest), copy = false)

        reason shouldBe "Cannot rewrite an immutable commit"
    }

    @Test
    fun `a copy-modifier drag under SOURCE scope is still exempt - duplicate never rewrites the moved entries`() {
        val source = entry("aaaaaaaa")
        val immutableChild = entry("bbbbbbbb", immutable = true, parentIds = listOf(source.id))
        val dest = entry("cccccccc")
        val entries = listOf(source, immutableChild, dest)
        val context = DragContext.forDrag(entries, DragPayload.Commit(listOf(source)), RebaseSourceMode.SOURCE)

        context.rejectionReason(DropTarget.CommitRow(dest), copy = true).shouldBeNull()
    }

    @Test
    fun `SOURCE scope excludes descendants as destinations too, not just the dragged entry`() {
        val source = entry("aaaaaaaa")
        val child = entry("bbbbbbbb", parentIds = listOf(source.id))
        val context = DragContext.forDrag(
            listOf(source, child),
            DragPayload.Commit(listOf(source)),
            RebaseSourceMode.SOURCE
        )

        val reason = context.rejectionReason(DropTarget.CommitRow(child), copy = false)

        reason shouldBe "That would create a cycle"
    }

    // endregion

    // region WorkingCopyRef payload (jj-idea-pk2c)

    @Test
    fun `a WorkingCopyRef drag is never subject to the cycle or source-immutability checks`() {
        val a = entry("aaaaaaaa", immutable = true)
        val b = entry("bbbbbbbb")
        val context = DragContext.forDrag(listOf(a, b), DragPayload.WorkingCopyRef(a))

        context.rejectionReason(DropTarget.CommitRow(b), copy = false).shouldBeNull()
    }

    @Test
    fun `dropping the @ marker onto its own row is a silent self-drop`() {
        val a = entry("aaaaaaaa")
        val context = DragContext.forDrag(listOf(a), DragPayload.WorkingCopyRef(a))

        context.rejectionReason(DropTarget.CommitRow(a), copy = false) shouldBe ""
    }

    @Test
    fun `dropping the @ marker into a different repository is rejected with an explanatory reason`() {
        val a = entry("aaaaaaaa", repo = repoA)
        val destInOtherRepo = entry("bbbbbbbb", repo = repoB)
        val context = DragContext.forDrag(listOf(a), DragPayload.WorkingCopyRef(a))

        val reason = context.rejectionReason(DropTarget.CommitRow(destInOtherRepo), copy = false)

        reason shouldBe "Cannot drop across repositories"
    }

    @Test
    fun `dropping the @ marker onto an immutable commit's centre is rejected - no dialog (jj-idea-d3u5)`() {
        val a = entry("aaaaaaaa")
        val immutableDest = entry("bbbbbbbb", immutable = true)
        val context = DragContext.forDrag(listOf(a, immutableDest), DragPayload.WorkingCopyRef(a))

        val reason = context.rejectionReason(DropTarget.CommitRow(immutableDest), copy = false)

        reason shouldBe "bbbbbbbb is immutable"
    }

    @Test
    fun `dropping the @ marker in the band above an immutable commit is always allowed - jj new never rewrites it`() {
        val a = entry("aaaaaaaa")
        val immutableDest = entry("bbbbbbbb", immutable = true)
        val context = DragContext.forDrag(listOf(a, immutableDest), DragPayload.WorkingCopyRef(a))

        context.rejectionReason(DropTarget.Gap(immutableDest, DropZone.INSERT_BEFORE), copy = false).shouldBeNull()
    }

    @Test
    fun `dropping the @ marker onto a mutable commit's centre is not rejected`() {
        val a = entry("aaaaaaaa")
        val mutableDest = entry("bbbbbbbb")
        val context = DragContext.forDrag(listOf(a, mutableDest), DragPayload.WorkingCopyRef(a))

        context.rejectionReason(DropTarget.CommitRow(mutableDest), copy = false).shouldBeNull()
    }

    // endregion

    // region Files payload (jj-idea-yvry, -b2oi)

    private val change = mockk<Change>(relaxed = true)

    @Test
    fun `files dropped onto their own owning change is a silent no-op`() {
        val owner = entry("aaaaaaaa")
        val files = DragPayload.Files(owner, listOf(change))
        val context = DragContext.forDrag(listOf(owner), files)

        context.rejectionReason(DropTarget.CommitRow(owner), copy = false) shouldBe ""
    }

    @Test
    fun `files dropped onto a different mutable change is allowed - squash`() {
        val owner = entry("aaaaaaaa")
        val dest = entry("bbbbbbbb")
        val files = DragPayload.Files(owner, listOf(change))
        val context = DragContext.forDrag(listOf(owner, dest), files)

        context.rejectionReason(DropTarget.CommitRow(dest), copy = false).shouldBeNull()
    }

    @Test
    fun `files dropped onto an immutable destination is rejected - squash rewrites it`() {
        val owner = entry("aaaaaaaa")
        val immutableDest = entry("bbbbbbbb", immutable = true)
        val files = DragPayload.Files(owner, listOf(change))
        val context = DragContext.forDrag(listOf(owner, immutableDest), files)

        val reason = context.rejectionReason(DropTarget.CommitRow(immutableDest), copy = false)

        reason shouldBe "bbbbbbbb is immutable"
    }

    @Test
    fun `files with an immutable owner cannot be squashed anywhere - squash rewrites the owner too`() {
        val immutableOwner = entry("aaaaaaaa", immutable = true)
        val dest = entry("bbbbbbbb")
        val files = DragPayload.Files(immutableOwner, listOf(change))
        val context = DragContext.forDrag(listOf(immutableOwner, dest), files)

        val reason = context.rejectionReason(DropTarget.CommitRow(dest), copy = false)

        reason shouldBe "Cannot rewrite an immutable commit"
    }

    @Test
    fun `files dropped in a gap on their own change is allowed - split`() {
        val owner = entry("aaaaaaaa")
        val files = DragPayload.Files(owner, listOf(change))
        val context = DragContext.forDrag(listOf(owner), files)

        context.rejectionReason(DropTarget.Gap(owner, DropZone.INSERT_AFTER), copy = false).shouldBeNull()
        context.rejectionReason(DropTarget.Gap(owner, DropZone.INSERT_BEFORE), copy = false).shouldBeNull()
    }

    @Test
    fun `files dropped in a gap on a different change is rejected by name - the b2oi acceptance message`() {
        val owner = entry("aaaaaaaa")
        val other = entry("bbbbbbbb")
        val files = DragPayload.Files(owner, listOf(change))
        val context = DragContext.forDrag(listOf(owner, other), files)

        val reason = context.rejectionReason(DropTarget.Gap(other, DropZone.INSERT_AFTER), copy = false)

        reason shouldBe "Files can only be split out next to their own change"
    }

    @Test
    fun `files with an immutable owner cannot be split either - split rewrites the owner`() {
        val immutableOwner = entry("aaaaaaaa", immutable = true)
        val files = DragPayload.Files(immutableOwner, listOf(change))
        val context = DragContext.forDrag(listOf(immutableOwner), files)

        val reason = context.rejectionReason(DropTarget.Gap(immutableOwner, DropZone.INSERT_AFTER), copy = false)

        reason shouldBe "Cannot rewrite an immutable commit"
    }

    @Test
    fun `files dropped onto a ref chip has no rejection - resolveDropOperation already yields no operation`() {
        val owner = entry("aaaaaaaa")
        val dest = entry("bbbbbbbb")
        val files = DragPayload.Files(owner, listOf(change))
        val context = DragContext.forDrag(listOf(owner, dest), files)

        context.rejectionReason(DropTarget.RefChip(dest.repo, dest.id, Bookmark("main")), copy = false).shouldBeNull()
    }

    // endregion
}
