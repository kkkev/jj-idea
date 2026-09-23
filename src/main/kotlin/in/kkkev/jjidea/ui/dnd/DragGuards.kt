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
    /** The full set of commits [sourceMode] would move, for a [DragPayload.Commit] drag - empty
     * for every other payload kind. Doubles as the cycle-exclusion set ([rejectionReason] rejects
     * any of these as a destination) and, exposed publicly, as the input a live "these rows would
     * move" highlight reads (jj-idea-d3u5, `ui/log/JujutsuLogTableDnD.kt`'s `applyDragHighlight`) -
     * one computation serving both, see [forDrag]'s doc. */
    val movedIds: Set<ChangeId>,
    private val invalidInsertBeforeIds: Set<ChangeId>,
    private val invalidInsertAfterIds: Set<ChangeId>,
    /** The scope [forDrag] was built with (jj-idea-j8ij) - read once per gesture from the drag
     * scope toolbar picker, not re-read per mouse-move. Only meaningful for a [DragPayload.Commit]
     * drag; callers building a [DropOperation.Rebase] pass this straight through. */
    val sourceMode: RebaseSourceMode
) {
    /** How many commits [sourceMode] will actually move - [movedIds]' size. Exposed alongside it
     * so a caller building [DropOperation.Rebase]'s label doesn't need to call `.size` itself. */
    val movedCount: Int get() = movedIds.size

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
        if (target.id in sourceIds) return ""
        if (target.id in movedIds) return "That would create a cycle"
        if (!copy && sourceHasImmutable && target !is DropTarget.RefChip && target !is DropTarget.TagChip) {
            return "Cannot rewrite an immutable commit"
        }
        (payload as? DragPayload.Files)?.let { return filesRejectionReason(it, target) }
        return when (target) {
            // Keyed by the RebaseDestinationMode the zone will actually produce (toDestinationMode),
            // not by DropZone's own screen-position name - see toDestinationMode's doc for why
            // those two axes don't line up one-to-one.
            is DropTarget.Gap -> when (target.edge.toDestinationMode()) {
                RebaseDestinationMode.INSERT_BEFORE -> immutabilityReason(target.entry, invalidInsertBeforeIds)
                RebaseDestinationMode.INSERT_AFTER -> immutabilityReason(target.entry, invalidInsertAfterIds)
                RebaseDestinationMode.ONTO -> null
            }
            // A WorkingCopyRef dropped onto an immutable row can't `jj edit` it - jj-idea-d3u5,
            // replacing the modal "Edit / New on Top" dialog jj-idea-pk2c used to show here with an
            // upfront reject. Deliberately does NOT extend to a Gap target for the same payload: the
            // Gap cell resolves to DropOperation.NewChangeOnTop (`jj new`), which never rewrites its
            // parent, so there's nothing to guard - dropping @ in the band above an immutable commit
            // must always be allowed, and simply has no branch checking it here.
            is DropTarget.CommitRow -> if (payload is DragPayload.WorkingCopyRef && target.entry.immutable) {
                "${target.entry.id.short} is immutable"
            } else {
                null
            }
            is DropTarget.RefChip, is DropTarget.TagChip -> null
        }
    }

    /**
     * The [DragPayload.Files]-specific half of [rejectionReason] (jj-idea-yvry, -b2oi): a
     * `CommitRow` squashes [files] into that row, a `Gap` splits them out - but only when the gap
     * borders [DragPayload.Files.owner] itself, per design section 1's "a split is only meaningful
     * at a gap bordering the files' own change". [resolveDropOperation]'s own owner check
     * (`DropOperation.kt`) is the dispatch backstop for that same rule; this is what turns it into
     * a *named* rejection the log table's [in.kkkev.jjidea.ui.log.RejectOverlay] can show, instead
     * of the silent "no operation" a guardless reject produces.
     */
    private fun filesRejectionReason(files: DragPayload.Files, target: DropTarget): String? = when (target) {
        is DropTarget.CommitRow -> when {
            target.entry.id == files.owner.id -> "" // squashing into the owning change itself - a no-op
            files.owner.immutable -> "Cannot rewrite an immutable commit"
            target.entry.immutable -> "${target.entry.id.short} is immutable"
            else -> null
        }
        is DropTarget.Gap -> when {
            target.entry.id != files.owner.id -> "Files can only be split out next to their own change"
            files.owner.immutable -> "Cannot rewrite an immutable commit"
            else -> null
        }
        is DropTarget.RefChip, is DropTarget.TagChip -> null
    }

    private fun immutabilityReason(entry: LogEntry, invalidIds: Set<ChangeId>): String? =
        if (entry.id in invalidIds) "${entry.id.short} is immutable" else null

    companion object {
        /**
         * Build the guard state for a drag of [payload] against the currently-loaded [allEntries],
         * with [sourceMode] picking `-r`/`-s`/`-b` (jj-idea-j8ij; ignored for anything but a
         * [DragPayload.Commit] drag). Only [DragPayload.Commit] populates the cycle/
         * source-immutability checks - the other payload kinds move a pointer (bookmark/tag/`@`) or
         * file content, none of which can create a DAG cycle or need rewrite an immutable *source*.
         * [sourceIds] does still carry a [DragPayload.WorkingCopyRef]'s own single id (jj-idea-pk2c),
         * since it's the only input [rejectionReason]'s self-drop check reads - without it, dropping
         * the `@` marker back onto its own row would resolve to a no-op `jj edit` of the revision
         * it's already on, instead of the silent reject every other payload kind gets for the same
         * gesture.
         *
         * [DragContext.movedIds] is [RebaseSimulator.excludedDestinationIds]'s result, which *is*
         * the full moved-id set for [sourceMode] (sources themselves for `-r`; sources plus
         * descendants for `-s`; the whole branch for `-b` - see that function's doc) - one
         * computation serving the cycle guard, [DragContext.movedCount], and (jj-idea-d3u5) the
         * live drag highlight. [sourceHasImmutable] is deliberately
         * checked against that same moved set, not just the dragged entries: under `-s`/`-b` the
         * descendants/branch get rewritten too, so a mutable tip dragged with `-b` selected must
         * still be rejected if an ancestor elsewhere in its branch is immutable - checking only the
         * dragged entries would silently let that rewrite attempt through.
         */
        fun forDrag(
            allEntries: List<LogEntry>,
            payload: DragPayload,
            sourceMode: RebaseSourceMode = RebaseSourceMode.REVISION
        ): DragContext {
            val sourceIds = when (payload) {
                is DragPayload.Commit -> payload.entries.map { it.id }.toSet()
                is DragPayload.WorkingCopyRef -> setOf(payload.entry.id)
                else -> emptySet()
            }
            val movedIds = if (payload is DragPayload.Commit) {
                RebaseSimulator.excludedDestinationIds(allEntries, sourceIds, sourceMode)
            } else {
                emptySet()
            }
            val sourceHasImmutable = if (payload is DragPayload.Commit) {
                val entryById = allEntries.associateBy { it.id }
                movedIds.any { entryById[it]?.immutable == true }
            } else {
                false
            }
            // invalidInsertBeforeIds/-AfterIds are only ever consulted by the Gap branch of a
            // Commit drag's rejectionReason (a rebase insert) - BookmarkRef/TagRef never produce a
            // Gap target, Files has its own gap rule (filesRejectionReason), and a WorkingCopyRef's
            // Gap cell (jj-idea-d3u5) is deliberately never immutability-guarded (see
            // rejectionReason's CommitRow branch doc), so staying empty for it is correct, not just
            // an optimisation. Skipping this pass for those payloads is what makes forDrag O(1) for
            // them, not just "cheap" - see DragContextScaleTest / the Files-payload variant of it.
            val invalidInsertBeforeIds = if (payload is DragPayload.Commit) {
                invalidDestinationIds(allEntries, RebaseDestinationMode.INSERT_BEFORE)
            } else {
                emptySet()
            }
            val invalidInsertAfterIds = if (payload is DragPayload.Commit) {
                invalidDestinationIds(allEntries, RebaseDestinationMode.INSERT_AFTER)
            } else {
                emptySet()
            }
            return DragContext(
                payload = payload,
                sourceIds = sourceIds,
                sourceHasImmutable = sourceHasImmutable,
                movedIds = movedIds,
                invalidInsertBeforeIds = invalidInsertBeforeIds,
                invalidInsertAfterIds = invalidInsertAfterIds,
                sourceMode = sourceMode
            )
        }
    }
}
