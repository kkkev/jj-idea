package `in`.kkkev.jjidea.ui.dnd

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.bookmark.executeMove
import `in`.kkkev.jjidea.actions.bookmark.openPushDialogFor
import `in`.kkkev.jjidea.actions.change.executeDuplicate
import `in`.kkkev.jjidea.actions.change.executeRebase
import `in`.kkkev.jjidea.actions.filechange.performFileSplit
import `in`.kkkev.jjidea.actions.filechange.performFileSquashInto
import `in`.kkkev.jjidea.actions.tag.executeSetTag
import `in`.kkkev.jjidea.jj.RebaseDestinationMode
import `in`.kkkev.jjidea.jj.RebaseSourceMode
import `in`.kkkev.jjidea.jj.Remote
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.ui.rebase.RebaseSpec
import `in`.kkkev.jjidea.vcs.filePath

/**
 * The seam where a resolved [DropOperation] would actually be carried out - `jj rebase`,
 * `jj bookmark set`, opening a pre-filled dialog, and so on, per
 * `docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 3.
 *
 * [supports] is consulted on every mouse-move (via the log table's target checker) and must stay
 * O(1) - a type check against [operation], not any work toward performing it. [perform] does the
 * actual work and is called once, on drop. Splitting these (rather than a single nullable function)
 * exists so an operation this object doesn't yet handle - e.g. [DropOperation.EditWorkingCopy]
 * before jj-idea-pk2c lands - rejects cleanly with no indicator, instead of lighting up a tooltip
 * for a drop that then does nothing on release.
 */
interface DropPerformer {
    /** Whether this performer would actually do something for [operation] - a pure type check. */
    fun supports(operation: DropOperation): Boolean

    /** Carry out [operation]. Only ever called when [supports] just returned `true` for it. */
    fun perform(operation: DropOperation): Boolean
}

/**
 * [forLogTable] wires the log table's drop operations to the command layer. jj-idea-8fxs wired
 * [DropOperation.Rebase] to `jj rebase`; jj-idea-p6nb wires [DropOperation.Duplicate] to
 * `jj duplicate`; jj-idea-ibth wires [DropOperation.MoveBookmark]/[DropOperation.MoveTag];
 * jj-idea-vdwh wires [DropOperation.Push]; jj-idea-yvry/-b2oi wire
 * [DropOperation.SquashFiles]/[DropOperation.SplitFiles] to the pre-filled Squash/Split dialogs.
 * Every other [DropOperation] variant is unwired here and named with the bead that will wire it.
 */
object DropPerformers {
    fun forLogTable(project: Project): DropPerformer = object : DropPerformer {
        override fun supports(operation: DropOperation): Boolean = when (operation) {
            is DropOperation.Rebase -> true
            is DropOperation.Duplicate -> true
            is DropOperation.MoveBookmark -> true
            is DropOperation.MoveTag -> true
            is DropOperation.EditWorkingCopy -> false // jj-idea-pk2c
            is DropOperation.Push -> true
            is DropOperation.SquashFiles -> true
            is DropOperation.SplitFiles -> true
        }

        override fun perform(operation: DropOperation): Boolean = when (operation) {
            is DropOperation.Rebase -> {
                executeRebase(operation.destination.repo, operation.toRebaseSpec())
                true
            }
            is DropOperation.Duplicate -> {
                val spec = operation.toDuplicateSpec()
                executeDuplicate(project, operation.destination.repo, spec.revisions, spec.destinations, spec.mode)
                true
            }
            is DropOperation.MoveBookmark -> {
                val repo = operation.destination.repo
                executeMove(repo, operation.bookmark, operation.destination.id, allowBackwards = false)
                true
            }
            is DropOperation.MoveTag -> {
                executeSetTag(operation.destination.repo, operation.tag, operation.destination.id, allowMove = false)
                true
            }
            is DropOperation.EditWorkingCopy -> false // jj-idea-pk2c
            is DropOperation.Push -> {
                openPushDialogFor(operation.entry.repo, operation.bookmark, Remote(operation.remote))
                true
            }
            is DropOperation.SquashFiles -> {
                performFileSquashInto(operation.files.owner, operation.destination, operation.selectedFiles)
                true
            }
            is DropOperation.SplitFiles -> {
                performFileSplit(project, operation.files.owner, operation.selectedFiles, operation.toNewParent())
                true
            }
        }
    }
}

/** The [in.kkkev.jjidea.jj.CommandExecutor]-facing file set a [DragPayload.Files] payload carries. */
private val DropOperation.SquashFiles.selectedFiles get() = files.changes.map { it.filePath }.toSet()
private val DropOperation.SplitFiles.selectedFiles get() = files.changes.map { it.filePath }.toSet()

/**
 * Maps this [DropOperation.SplitFiles]' drop edge to [in.kkkev.jjidea.ui.split.SplitDialog]'s
 * `newParent` flag (jj-idea-b2oi). The gap always borders the files' own change (guarded by
 * [DragContext.rejectionReason] and [resolveDropOperation] before this is ever built), so [gap]'s
 * [DropTarget.Gap.edge] names *which side* of that single row the drop landed on - the log
 * renders newest-first, so the **bottom** band (visually below, [DropZone.INSERT_AFTER]) is the
 * parent-side slot and the **top** band (visually above, [DropZone.INSERT_BEFORE]) is the
 * child-side slot:
 * - bottom band -> `newParent = true` (`jj split -B`): the ticked files become a **new commit
 *   inserted as the owning change's parent**, per [in.kkkev.jjidea.ui.split.SplitDialog]'s KDoc.
 * - top band -> `newParent = false` (the dialog's default mode): the ticked files become a
 *   **new child** commit instead.
 */
internal fun DropOperation.SplitFiles.toNewParent(): Boolean = when (gap.edge) {
    DropZone.INSERT_AFTER -> true
    DropZone.INSERT_BEFORE -> false
    DropZone.ONTO -> error("DropTarget.Gap.edge is never ONTO - enforced by its own init check")
}

/**
 * `sourceMode` is hardcoded to [RebaseSourceMode.REVISION] - not arbitrary, it must match what
 * [DragContext.forDrag] assumed when it computed the cycle-exclusion set that
 * [DragContext.rejectionReason] already guarded this drop against. Picking `-s`/`-b` from a drag is
 * jj-idea-j8ij, blocked on this bead.
 */
internal fun DropOperation.Rebase.toRebaseSpec() = RebaseSpec(
    revisions = sources.map { it.id },
    destinations = listOf(destination.id),
    sourceMode = RebaseSourceMode.REVISION,
    destinationMode = mode
)

/**
 * The `jj duplicate` counterpart of [RebaseSpec] - deliberately its own type rather than reusing
 * [in.kkkev.jjidea.ui.duplicate.DuplicateDialog.DuplicateSpec], which carries only a destination
 * and mode (the dialog already knows its own source revisions as a separate parameter); this one
 * needs [revisions] alongside, the same shape [RebaseSpec] already carries for the same reason.
 */
internal data class DuplicateSpec(
    val revisions: List<Revision>,
    val destinations: List<Revision>,
    val mode: RebaseDestinationMode
)

/**
 * [DropOperation.Duplicate] carries the same `(sources, destination, mode)` shape as
 * [DropOperation.Rebase], mapped the same way - `jj duplicate` just has no source-mode axis
 * ([in.kkkev.jjidea.jj.CommandExecutor.duplicate] takes positional revisions, never `-s`/`-b`).
 */
internal fun DropOperation.Duplicate.toDuplicateSpec() = DuplicateSpec(
    revisions = sources.map { it.id },
    destinations = listOf(destination.id),
    mode = mode
)
