package `in`.kkkev.jjidea.ui.dnd

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeIdentity
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RebaseDestinationMode
import `in`.kkkev.jjidea.jj.Tag

/**
 * Which of a row's three vertical zones a drop landed in
 * (`docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 2). [ONTO] owns the centre half
 * of the row; [INSERT_BEFORE]/[INSERT_AFTER] own the top/bottom quarter respectively, named for
 * their **screen position** (top = "before" in reading order, bottom = "after") - see
 * [DropZones] for the geometry and [ZoneHysteresis] for how flicker at the boundary is avoided.
 *
 * This is deliberately *not* the same axis as [RebaseDestinationMode.INSERT_BEFORE]/
 * [RebaseDestinationMode.INSERT_AFTER] - see [toDestinationMode] for the (non-identity)
 * conversion between them.
 */
enum class DropZone { INSERT_BEFORE, ONTO, INSERT_AFTER }

/**
 * Convert a screen-position [DropZone] to the [RebaseDestinationMode] that actually produces
 * that position in the rendered log - **not** a same-name identity mapping, despite both using
 * "before"/"after". The log renders newest-first (children above parents), so:
 * - the log's **top** band (visually above the destination) is where the result of `jj rebase
 *   -A` (`INSERT_AFTER` - "insert after the destination, before its current children") lands,
 *   since that's exactly the slot between the destination and its children.
 * - the log's **bottom** band (visually below) is where `jj rebase -B`
 *   (`INSERT_BEFORE` - "insert before the destination, after its parents") lands, the slot
 *   between the destination and its parents.
 *
 * A naive identity mapping (top zone -> `RebaseDestinationMode.INSERT_BEFORE`) was shipped
 * originally and produced a rebase that landed on the visually opposite side of the drop - see
 * the fix in jj-idea-6jvh's follow-up commit for the manual-testing report that caught it.
 */
fun DropZone.toDestinationMode(): RebaseDestinationMode = when (this) {
    DropZone.INSERT_BEFORE -> RebaseDestinationMode.INSERT_AFTER
    DropZone.INSERT_AFTER -> RebaseDestinationMode.INSERT_BEFORE
    DropZone.ONTO -> RebaseDestinationMode.ONTO
}

/**
 * Where a drag gesture is being dropped, independent of which payload is being dragged - the other
 * half of the payload/target model, see [DragPayload]'s doc.
 *
 * Only ever needs plain repo-scoped identity ([ChangeIdentity]) - [resolveDropOperation] and
 * [DragContext.rejectionReason] read a target's [repo]/[id], never a hydrated commit, except
 * [CommitRow]/[Gap]'s own rebase-destination and immutability needs, which keep their [LogEntry]
 * directly. [RefChip]/[TagChip] carry identity without one, so a bookmarks-panel node whose change
 * falls outside the currently-loaded log window is still a valid target (jj-idea-0rdm).
 */
sealed interface DropTarget : ChangeIdentity {
    /** The drop landed in [entry]'s centre band - "onto" this commit. */
    data class CommitRow(val entry: LogEntry) : DropTarget {
        override val repo get() = entry.repo
        override val id get() = entry.id
    }

    /**
     * The drop landed in [entry]'s top or bottom band, per [edge]. Bound **per row, not per visual
     * gap** (design section 2's correctness trap): the log table shows a linearised view of the
     * DAG, so table-adjacent rows are not necessarily DAG-adjacent, and "insert after row N" only
     * coincides with "insert before row N+1" when N+1 is N's sole parent and N is N+1's sole child.
     * The top band of row N therefore always means [DropZone.INSERT_BEFORE] on [entry], regardless
     * of what happens to be drawn immediately above it.
     */
    data class Gap(val entry: LogEntry, val edge: DropZone) : DropTarget {
        override val repo get() = entry.repo
        override val id get() = entry.id

        init {
            require(edge != DropZone.ONTO) { "Gap edge must be INSERT_BEFORE or INSERT_AFTER, not ONTO" }
        }
    }

    /** A bookmark chip, whether rendered as a log-table chip or a bookmarks-panel node. */
    data class RefChip(override val repo: JujutsuRepository, override val id: ChangeId, val bookmark: Bookmark) :
        DropTarget

    /** A tag chip, whether rendered as a log-table chip or a bookmarks-panel node. */
    data class TagChip(override val repo: JujutsuRepository, override val id: ChangeId, val tag: Tag) : DropTarget
}
