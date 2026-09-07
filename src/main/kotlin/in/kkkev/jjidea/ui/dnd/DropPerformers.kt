package `in`.kkkev.jjidea.ui.dnd

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.bookmark.executeMove
import `in`.kkkev.jjidea.actions.bookmark.openPushDialogFor
import `in`.kkkev.jjidea.actions.change.executeDuplicate
import `in`.kkkev.jjidea.actions.change.executeRebase
import `in`.kkkev.jjidea.actions.tag.executeSetTag
import `in`.kkkev.jjidea.jj.RebaseDestinationMode
import `in`.kkkev.jjidea.jj.RebaseSourceMode
import `in`.kkkev.jjidea.jj.Remote
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.ui.rebase.RebaseSpec

/**
 * The seam where a resolved [DropOperation] would actually be carried out - `jj rebase`,
 * `jj bookmark set`, opening a pre-filled dialog, and so on, per
 * `docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 3.
 *
 * [supports] is consulted on every mouse-move (via the log table's target checker) and must stay
 * O(1) - a type check against [operation], not any work toward performing it. [perform] does the
 * actual work and is called once, on drop. Splitting these (rather than a single nullable function)
 * exists so an operation this object doesn't yet handle - e.g. [DropOperation.SquashFiles] before
 * jj-idea-yvry lands - rejects cleanly with no indicator, instead of lighting up a tooltip for a
 * drop that then does nothing on release.
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
 * jj-idea-vdwh wires [DropOperation.Push]. Every other [DropOperation] variant is unwired here and
 * named with the bead that will wire it.
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
            is DropOperation.SquashFiles -> false // jj-idea-yvry
            is DropOperation.SplitFiles -> false // jj-idea-yvry
        }

        override fun perform(operation: DropOperation): Boolean = when (operation) {
            is DropOperation.Rebase -> {
                executeRebase(project, operation.destination.repo, operation.toRebaseSpec())
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
            is DropOperation.SquashFiles -> false // jj-idea-yvry
            is DropOperation.SplitFiles -> false // jj-idea-yvry
        }
    }
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
