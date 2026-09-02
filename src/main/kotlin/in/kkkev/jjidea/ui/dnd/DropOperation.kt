package `in`.kkkev.jjidea.ui.dnd

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RebaseDestinationMode
import `in`.kkkev.jjidea.jj.Tag

/**
 * The operation a `(payload, target)` pair resolves to - [resolveDropOperation] is the single
 * dispatch point implementing the full matrix from
 * `docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 1, mirroring how
 * [in.kkkev.jjidea.ui.log.LogClickTarget.resolve] already centralizes click resolution. [label] is
 * the human-readable name of the operation, passed to `DnDEvent.setDropPossible(true, label)` so
 * the platform's inline tooltip names the exact operation a drop would perform - not just that one
 * is available.
 *
 * This bead (jj-idea-6jvh) only builds the dispatch table itself; nothing in this package invokes
 * `CommandExecutor` - each gesture bead (jj-idea-8fxs, -ibth, -yvry, ...) wires one [DropOperation]
 * variant to the command layer once undo (jj-idea-v9zp) is in place for the immediate-apply cases.
 */
sealed interface DropOperation {
    val label: String

    /** Plain rebase - the commit becomes a child of [destination] (`ONTO`) or is inserted at [mode]. */
    data class Rebase(val sources: List<LogEntry>, val destination: LogEntry, val mode: RebaseDestinationMode) :
        DropOperation {
        override val label get() = rebaseLabel("Rebase", sources, destination, mode)
    }

    /** Copy-modifier drag (jj-idea-p6nb): same placement as [Rebase], but duplicates rather than moves. */
    data class Duplicate(val sources: List<LogEntry>, val destination: LogEntry, val mode: RebaseDestinationMode) :
        DropOperation {
        override val label get() = rebaseLabel("Duplicate", sources, destination, mode)
    }

    /** A bookmark or tag chip (or a dragged commit dropped onto one) moves [bookmark] to [destination]. */
    data class MoveBookmark(val bookmark: Bookmark, val destination: LogEntry) : DropOperation {
        override val label get() = "Move bookmark ${bookmark.name} to ${destination.id.short}"
    }

    data class MoveTag(val tag: Tag, val destination: LogEntry) : DropOperation {
        override val label get() = "Move tag ${tag.name} to ${destination.id.short}"
    }

    /** The working-copy `@` marker is dragged onto [destination] - runs `jj edit`. */
    data class EditWorkingCopy(val destination: LogEntry) : DropOperation {
        override val label get() = "Edit ${destination.id.short}"
    }

    /** A local bookmark chip dragged onto its own `name@remote` chip - always dialog-gated, see design section 7. */
    data class Push(val bookmark: Bookmark, val remote: String) : DropOperation {
        override val label get() = "Push ${bookmark.name} to $remote"
    }

    /** Files dropped on a row's centre - squashed into [destination] (dialog-gated). */
    data class SquashFiles(val files: DragPayload.Files, val destination: LogEntry) : DropOperation {
        override val label get() = "Squash ${files.changes.size} file(s) into ${destination.id.short}"
    }

    /** Files dropped in a gap bordering their own change - split out into a new change there (dialog-gated). */
    data class SplitFiles(val files: DragPayload.Files, val gap: DropTarget.Gap) : DropOperation {
        override val label get() = "Split ${files.changes.size} file(s) out of ${files.owner.id.short}"
    }
}

private fun rebaseLabel(
    verb: String,
    sources: List<LogEntry>,
    destination: LogEntry,
    mode: RebaseDestinationMode
): String {
    val what = if (sources.size == 1) sources.single().id.short else "${sources.size} commits"
    return when (mode) {
        RebaseDestinationMode.ONTO -> "$verb $what onto ${destination.id.short}"
        RebaseDestinationMode.INSERT_BEFORE -> "$verb $what, inserting before ${destination.id.short}"
        RebaseDestinationMode.INSERT_AFTER -> "$verb $what, inserting after ${destination.id.short}"
    }
}

/**
 * Resolve what dropping [payload] on [target] would do, or `null` if that pairing has no
 * operation - the design's dispatch table (section 1) has several deliberately empty cells (e.g. a
 * tag dropped in a gap). [copy] is `true` when the drag is being performed with the platform's copy
 * modifier held (`DnDActionInfo.getAction() == DnDAction.COPY`), which is how the plain-rebase cell
 * and the duplicate cell share one code path.
 *
 * Callers are expected to have already run [DragContext.rejectionReason] against [target] - this
 * function only answers "what operation, if any" and does not itself re-check immutability, cycles,
 * self-drop, or cross-repository placement.
 */
fun resolveDropOperation(payload: DragPayload, target: DropTarget, copy: Boolean): DropOperation? = when (payload) {
    is DragPayload.Commit -> when (target) {
        is DropTarget.CommitRow -> rebaseOrDuplicate(payload.entries, target.entry, RebaseDestinationMode.ONTO, copy)
        is DropTarget.Gap -> rebaseOrDuplicate(payload.entries, target.entry, target.edge.toDestinationMode(), copy)
        is DropTarget.RefChip -> payload.entries.singleOrNull()?.let { DropOperation.MoveBookmark(target.bookmark, it) }
    }

    is DragPayload.BookmarkRef -> when (target) {
        is DropTarget.CommitRow -> DropOperation.MoveBookmark(payload.bookmark, target.entry)
        is DropTarget.RefChip ->
            if (!payload.bookmark.name.isRemote && target.bookmark.name.isRemote) {
                DropOperation.Push(payload.bookmark, target.bookmark.name.remote)
            } else {
                null
            }
        is DropTarget.Gap -> null
    }

    is DragPayload.TagRef -> (target as? DropTarget.CommitRow)?.let { DropOperation.MoveTag(payload.tag, it.entry) }

    is DragPayload.WorkingCopyRef ->
        (target as? DropTarget.CommitRow)?.let { DropOperation.EditWorkingCopy(it.entry) }

    is DragPayload.Files -> when (target) {
        is DropTarget.CommitRow -> DropOperation.SquashFiles(payload, target.entry)
        is DropTarget.Gap -> if (target.entry.id ==
            payload.owner.id
        ) {
            DropOperation.SplitFiles(payload, target)
        } else {
            null
        }
        is DropTarget.RefChip -> null
    }
}

private fun rebaseOrDuplicate(
    sources: List<LogEntry>,
    destination: LogEntry,
    mode: RebaseDestinationMode,
    copy: Boolean
): DropOperation = if (copy) {
    DropOperation.Duplicate(sources, destination, mode)
} else {
    DropOperation.Rebase(sources, destination, mode)
}
