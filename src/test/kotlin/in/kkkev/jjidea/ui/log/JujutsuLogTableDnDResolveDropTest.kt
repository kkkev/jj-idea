package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RebaseDestinationMode
import `in`.kkkev.jjidea.ui.dnd.DragContext
import `in`.kkkev.jjidea.ui.dnd.DragPayload
import `in`.kkkev.jjidea.ui.dnd.DropOperation
import `in`.kkkev.jjidea.ui.dnd.DropTarget
import `in`.kkkev.jjidea.ui.dnd.DropZone
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * [resolveDrop] is the pure part of the log table's live drop resolution. jj-idea-ymuu: a real
 * guard rejection (cross-repo, cycle, immutable) must resolve with the same `(row, zone)` an
 * allowed drop would, carrying [DragContext.rejectionReason]'s reason - so the caller can paint a
 * reliable, Swing-painted "blocked" indicator at that row instead of depending solely on the
 * platform's native reject cursor (confirmed unreliable: in-app drag-over feedback runs off
 * OS-coalesced ticks, so a fast *or even a deliberately slow* drag across a rejected row could show
 * nothing at all).
 */
class JujutsuLogTableDnDResolveDropTest {
    private val repoA = mockk<JujutsuRepository>(relaxed = true)
    private val repoB = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(id: String, repo: JujutsuRepository = repoA) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("commit-$id"),
        underlyingDescription = "desc $id"
    )

    @Test
    fun `a drop into a different repository is Rejected with the guard's reason, at the hovered row`() {
        val source = entry("aaaaaaaa", repo = repoA)
        val destInOtherRepo = entry("bbbbbbbb", repo = repoB)
        val context = DragContext.forDrag(listOf(source), DragPayload.Commit(listOf(source)))

        val resolution = resolveDrop(
            payload = DragPayload.Commit(listOf(source)),
            target = DropTarget.CommitRow(destInOtherRepo),
            row = 7,
            copy = false,
            context = context
        )

        resolution shouldBe DropResolution.Rejected(
            row = 7,
            zone = DropZone.ONTO,
            reason = "Cannot drop across repositories"
        )
    }

    @Test
    fun `a self-drop is Rejected silently - empty reason, not a message`() {
        val a = entry("aaaaaaaa")
        val context = DragContext.forDrag(listOf(a), DragPayload.Commit(listOf(a)))

        val resolution = resolveDrop(
            payload = DragPayload.Commit(listOf(a)),
            target = DropTarget.CommitRow(a),
            row = 0,
            copy = false,
            context = context
        )

        resolution shouldBe DropResolution.Rejected(row = 0, zone = DropZone.ONTO, reason = "")
    }

    @Test
    fun `an allowed drop resolves to Allowed with the operation and zone`() {
        val a = entry("aaaaaaaa")
        val b = entry("bbbbbbbb")
        val context = DragContext.forDrag(listOf(a, b), DragPayload.Commit(listOf(a)))

        val resolution = resolveDrop(
            payload = DragPayload.Commit(listOf(a)),
            target = DropTarget.CommitRow(b),
            row = 3,
            copy = false,
            context = context
        )

        resolution shouldBe DropResolution.Allowed(
            row = 3,
            zone = DropZone.ONTO,
            operation = DropOperation.Rebase(listOf(a), b, RebaseDestinationMode.ONTO)
        )
    }
}
