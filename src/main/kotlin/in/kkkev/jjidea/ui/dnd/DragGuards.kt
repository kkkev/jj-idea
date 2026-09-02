package `in`.kkkev.jjidea.ui.dnd

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RebaseDestinationMode
import `in`.kkkev.jjidea.jj.RebaseSourceMode
import `in`.kkkev.jjidea.ui.duplicate.invalidDestinationIds
import `in`.kkkev.jjidea.ui.rebase.RebaseSimulator

/**
 * Guards a single drag gesture against the invalid drops enumerated in
 * `docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 6: immutability, cycles,
 * cross-repository drops, and self-drop. Built once per gesture via [forDrag] - which does the one
 * O(entries) pass needed (`RebaseSimulator.excludedDestinationIds`,
 * [in.kkkev.jjidea.ui.duplicate.invalidDestinationIds]) - so that [rejectionReason], called from
 * `DnDTargetChecker.update` on **every mouse-move** (design section 9's stated performance risk),
 * is pure `Set` lookups: O(1) per move regardless of log size.
 */
class DragContext private constructor(
    private val payload: DragPayload,
    private val sourceIds: Set<ChangeId>,
    private val sourceHasImmutable: Boolean,
    private val cycleExcludedIds: Set<ChangeId>,
    private val invalidInsertBeforeIds: Set<ChangeId>,
    private val invalidInsertAfterIds: Set<ChangeId>
) {
    /**
     * Why dropping [payload] on [target] must be rejected, or `null` if it's allowed. [copy]
     * mirrors [resolveDropOperation]'s parameter - a copy-modifier drag duplicates rather than
     * rewrites, so it is exempt from the "can't rewrite an immutable source" check (`jj duplicate`
     * never rewrites the commit(s) being duplicated).
     *
     * An empty string (as opposed to `null`) means "reject, but say nothing" - reserved for the
     * self-drop case: per the comment in the platform's own `RowsDnDSupport.java`, a drag gesture
     * always starts with the pointer over its own source, so that first instant must not show an
     * error tooltip (design section 6).
     */
    fun rejectionReason(target: DropTarget, copy: Boolean): String? {
        if (target.repo != payload.repo) return "Cannot drop across repositories"
        if (target.entry.id in sourceIds) return ""
        if (target.entry.id in cycleExcludedIds) return "That would create a cycle"
        if (!copy && sourceHasImmutable && target !is DropTarget.RefChip) {
            return "Cannot rewrite an immutable commit"
        }
        return when (target) {
            // Keyed by the RebaseDestinationMode the zone will actually produce (toDestinationMode),
            // not by DropZone's own screen-position name - see toDestinationMode's doc for why
            // those two axes don't line up one-to-one.
            is DropTarget.Gap -> when (target.edge.toDestinationMode()) {
                RebaseDestinationMode.INSERT_BEFORE -> immutabilityReason(target.entry, invalidInsertBeforeIds)
                RebaseDestinationMode.INSERT_AFTER -> immutabilityReason(target.entry, invalidInsertAfterIds)
                RebaseDestinationMode.ONTO -> null
            }
            is DropTarget.CommitRow, is DropTarget.RefChip -> null
        }
    }

    private fun immutabilityReason(entry: LogEntry, invalidIds: Set<ChangeId>): String? =
        if (entry.id in invalidIds) "${entry.id.short} is immutable" else null

    companion object {
        /**
         * Build the guard state for a drag of [payload] against the currently-loaded [allEntries].
         * Only [DragPayload.Commit] populates the cycle/source-immutability checks - the other
         * payload kinds move a pointer (bookmark/tag/`@`) or file content, none of which can create
         * a DAG cycle or need rewrite an immutable *source*.
         */
        fun forDrag(allEntries: List<LogEntry>, payload: DragPayload): DragContext {
            val sourceIds = (payload as? DragPayload.Commit)?.entries?.map { it.id }?.toSet() ?: emptySet()
            val sourceHasImmutable = (payload as? DragPayload.Commit)?.entries?.any { it.immutable } == true
            val cycleExcludedIds = if (payload is DragPayload.Commit) {
                RebaseSimulator.excludedDestinationIds(allEntries, sourceIds, RebaseSourceMode.REVISION)
            } else {
                emptySet()
            }
            return DragContext(
                payload = payload,
                sourceIds = sourceIds,
                sourceHasImmutable = sourceHasImmutable,
                cycleExcludedIds = cycleExcludedIds,
                invalidInsertBeforeIds = invalidDestinationIds(allEntries, RebaseDestinationMode.INSERT_BEFORE),
                invalidInsertAfterIds = invalidDestinationIds(allEntries, RebaseDestinationMode.INSERT_AFTER)
            )
        }
    }
}
