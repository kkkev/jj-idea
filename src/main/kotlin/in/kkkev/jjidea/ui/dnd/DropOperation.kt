package `in`.kkkev.jjidea.ui.dnd

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RebaseDestinationMode
import `in`.kkkev.jjidea.jj.RebaseSourceMode
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

    /**
     * Plain rebase - the commit becomes a child of [destination] (`ONTO`) or is inserted at
     * [mode]. [sourceMode] picks `-r`/`-s`/`-b` (jj-idea-j8ij; the View Options "Drag scope"
     * selector, read once per gesture) - [sources] itself is never expanded to include
     * descendants/the branch here: `jj rebase` computes that server-side from [sourceMode] and the
     * positional revisions in [in.kkkev.jjidea.ui.dnd.DropPerformers.toRebaseSpec]. [movedCount] is
     * how many commits [sourceMode] will actually move (>= [sources].size for `-s`/`-b`) - carried
     * separately because computing it needs the full loaded log
     * ([in.kkkev.jjidea.ui.rebase.RebaseSimulator.excludedDestinationIds], already paid for by
     * [DragContext.forDrag]'s cycle guard), which this package-pure function has no access to; it
     * exists purely so [label] can name the scope ("...and its 3 descendants...") instead of
     * silently moving more than the drag chip showed.
     */
    data class Rebase(
        val sources: List<LogEntry>,
        val destination: LogEntry,
        val mode: RebaseDestinationMode,
        val sourceMode: RebaseSourceMode = RebaseSourceMode.REVISION,
        val movedCount: Int = sources.size
    ) : DropOperation {
        override val label get() = rebaseLabel("Rebase", sources, destination, mode, sourceMode, movedCount)
    }

    /**
     * Copy-modifier drag (jj-idea-p6nb): same placement as [Rebase], but duplicates rather than
     * moves. Always `-r` scope - `jj duplicate` has no `-s`/`-b` axis (it takes positional
     * revisions only), so the drag-scope selector (jj-idea-j8ij) has no effect on a copy-modifier
     * drag; see [in.kkkev.jjidea.ui.dnd.DropPerformers.toDuplicateSpec]'s doc for the follow-up
     * this leaves open.
     */
    data class Duplicate(val sources: List<LogEntry>, val destination: LogEntry, val mode: RebaseDestinationMode) :
        DropOperation {
        override val label get() = rebaseLabel("Duplicate", sources, destination, mode)
    }

    /**
     * A bookmark or tag chip (or a dragged commit dropped onto one) moves [bookmark] to
     * [destination]. Labelled "Resolve" rather than "Move" when [bookmark] is conflicted
     * (jj-idea-bico) - re-pointing a conflicted bookmark at one of its own targets is how a drag
     * resolves it, not an ordinary move.
     */
    data class MoveBookmark(val bookmark: Bookmark, val destination: LogEntry) : DropOperation {
        override val label get() = if (bookmark.conflict) {
            "Resolve bookmark ${bookmark.name} to ${destination.id.short}"
        } else {
            "Move bookmark ${bookmark.name} to ${destination.id.short}"
        }
    }

    data class MoveTag(val tag: Tag, val destination: LogEntry) : DropOperation {
        override val label get() = "Move tag ${tag.name} to ${destination.id.short}"
    }

    /**
     * The working-copy `@` marker is dropped on [destination]'s centre band - runs `jj edit`.
     * Rejected outright by [DragContext.rejectionReason] if [destination] is immutable (jj-idea-d3u5:
     * no confirmation dialog - the marker drag uses the same upfront-reject vocabulary every other
     * gesture does, replacing the modal "Edit / New on Top / Cancel" prompt jj-idea-pk2c shipped
     * with). See [NewChangeOnTop] for the top-band sibling that's *never* rejected for immutability.
     */
    data class EditWorkingCopy(val destination: LogEntry) : DropOperation {
        override val label get() = "Edit ${destination.id.short}"
    }

    /**
     * The working-copy `@` marker is dropped in the band just above [destination] (the log's
     * newest-first rendering makes the top band the child-side slot - see
     * [DropZone.toDestinationMode]'s doc) - runs `jj new` with [destination] as the new change's
     * positional parent. Always allowed regardless of [destination]'s immutability: `jj new` never
     * rewrites its parent, only adds a child, so there is nothing to guard against (jj-idea-d3u5).
     */
    data class NewChangeOnTop(val destination: LogEntry) : DropOperation {
        override val label get() = "New change on top of ${destination.id.short}"
    }

    /**
     * A local bookmark chip dragged onto its own `name@remote` chip - always dialog-gated, see
     * design section 7. [repo] is carried only to name which repository the push runs in, the way
     * [MoveBookmark.destination]/[MoveTag.destination] already do for their operations - it no
     * longer needs a [LogEntry], since the `name@remote` chip's row may not be loaded (jj-idea-3xab).
     */
    data class Push(val bookmark: Bookmark, val remote: String, val repo: JujutsuRepository) : DropOperation {
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
    mode: RebaseDestinationMode,
    sourceMode: RebaseSourceMode = RebaseSourceMode.REVISION,
    movedCount: Int = sources.size
): String {
    val what = sourceScopeLabel(sources, sourceMode, movedCount)
    return when (mode) {
        RebaseDestinationMode.ONTO -> "$verb $what onto ${destination.id.short}"
        RebaseDestinationMode.INSERT_BEFORE -> "$verb $what, inserting before ${destination.id.short}"
        RebaseDestinationMode.INSERT_AFTER -> "$verb $what, inserting after ${destination.id.short}"
    }
}

/**
 * The "what's moving" clause of [rebaseLabel] - plain source count/id for `-r` (unchanged from
 * before jj-idea-j8ij), naming the extra scope for `-s`/`-b` so a sticky "Drag scope" setting
 * can't silently move more than the drag chip showed (`docs/design/preview-gating-and-dnd-sequencing.md`
 * batch 5's "anti-footgun" requirement).
 */
private fun sourceScopeLabel(sources: List<LogEntry>, sourceMode: RebaseSourceMode, movedCount: Int): String {
    val base = if (sources.size == 1) sources.single().id.short else "${sources.size} commits"
    return when (sourceMode) {
        RebaseSourceMode.REVISION -> base
        RebaseSourceMode.SOURCE -> {
            val descendants = movedCount - sources.size
            if (descendants > 0) {
                "$base and its $descendants descendant${if (descendants == 1) "" else "s"}"
            } else {
                base
            }
        }
        RebaseSourceMode.BRANCH -> if (sources.size == 1) {
            "the branch containing ${sources.single().id.short}"
        } else {
            "the branches containing $base"
        }
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
 *
 * [sourceMode]/[movedCount] (jj-idea-j8ij) only ever affect [DropOperation.Rebase] - defaulted so
 * every other call site (chip drags, files, `@`, and the ~15 pre-existing tests of this function)
 * is unaffected. [movedCount] is meaningless unless [sourceMode] is non-`REVISION`; see
 * [DropOperation.Rebase]'s doc for why it's threaded in rather than recomputed here.
 */
fun resolveDropOperation(
    payload: DragPayload,
    target: DropTarget,
    copy: Boolean,
    sourceMode: RebaseSourceMode = RebaseSourceMode.REVISION,
    movedCount: Int = (payload as? DragPayload.Commit)?.entries?.size ?: 0
): DropOperation? = when (payload) {
    is DragPayload.Commit -> when (target) {
        is DropTarget.CommitRow ->
            rebaseOrDuplicate(payload.entries, target.entry, RebaseDestinationMode.ONTO, copy, sourceMode, movedCount)
        is DropTarget.Gap -> rebaseOrDuplicate(
            payload.entries,
            target.entry,
            target.edge.toDestinationMode(),
            copy,
            sourceMode,
            movedCount
        )
        is DropTarget.RefChip -> payload.entries.singleOrNull()?.let { DropOperation.MoveBookmark(target.bookmark, it) }
        is DropTarget.TagChip -> payload.entries.singleOrNull()?.let { DropOperation.MoveTag(target.tag, it) }
    }

    is DragPayload.BookmarkRef -> when (target) {
        // Dropped back on the row it already sits on - a no-op, not an operation (mirrors the
        // deliberately-silent self-drop case DragContext.rejectionReason handles for a dragged
        // commit). Only a no-op when that row is the bookmark's *sole* target: a conflicted
        // bookmark has several, and re-pointing it at any one of them is a resolve, not a no-op
        // (jj-idea-bico).
        is DropTarget.CommitRow -> if (payload.targets == setOf(target.id)) {
            null
        } else {
            DropOperation.MoveBookmark(payload.bookmark, target.entry)
        }
        is DropTarget.RefChip ->
            if (!payload.bookmark.name.isRemote && target.bookmark.name.isRemote) {
                DropOperation.Push(payload.bookmark, target.bookmark.name.remote, target.repo)
            } else {
                null
            }
        is DropTarget.Gap, is DropTarget.TagChip -> null
    }

    is DragPayload.TagRef -> (target as? DropTarget.CommitRow)?.let {
        // Same conflicted-target reasoning as DragPayload.BookmarkRef above (jj-idea-bico).
        if (payload.targets == setOf(it.id)) null else DropOperation.MoveTag(payload.tag, it.entry)
    }

    is DragPayload.WorkingCopyRef -> when (target) {
        is DropTarget.CommitRow -> DropOperation.EditWorkingCopy(target.entry)
        is DropTarget.Gap -> if (target.edge == DropZone.INSERT_BEFORE) {
            DropOperation.NewChangeOnTop(target.entry)
        } else {
            null
        }
        is DropTarget.RefChip, is DropTarget.TagChip -> null
    }

    is DragPayload.Files -> when (target) {
        is DropTarget.CommitRow -> DropOperation.SquashFiles(payload, target.entry)
        is DropTarget.Gap -> if (target.entry.id ==
            payload.owner.id
        ) {
            DropOperation.SplitFiles(payload, target)
        } else {
            null
        }
        is DropTarget.RefChip, is DropTarget.TagChip -> null
    }
}

private fun rebaseOrDuplicate(
    sources: List<LogEntry>,
    destination: LogEntry,
    mode: RebaseDestinationMode,
    copy: Boolean,
    sourceMode: RebaseSourceMode,
    movedCount: Int
): DropOperation = if (copy) {
    // Duplicate is always -r - see DropOperation.Duplicate's doc.
    DropOperation.Duplicate(sources, destination, mode)
} else {
    DropOperation.Rebase(sources, destination, mode, sourceMode, movedCount)
}
