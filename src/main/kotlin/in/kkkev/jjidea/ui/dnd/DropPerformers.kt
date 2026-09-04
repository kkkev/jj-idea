package `in`.kkkev.jjidea.ui.dnd

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.change.executeRebase
import `in`.kkkev.jjidea.jj.RebaseSourceMode
import `in`.kkkev.jjidea.ui.rebase.RebaseSpec

/**
 * The seam where a resolved [DropOperation] would actually be carried out - `jj rebase`,
 * `jj bookmark set`, opening a pre-filled dialog, and so on, per
 * `docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 3.
 *
 * [supports] is consulted on every mouse-move (via the log table's target checker) and must stay
 * O(1) - a type check against [operation], not any work toward performing it. [perform] does the
 * actual work and is called once, on drop. Splitting these (rather than a single nullable function)
 * exists so an operation this object doesn't yet handle - e.g. [DropOperation.Duplicate] before
 * jj-idea-p6nb lands - rejects cleanly with no indicator, instead of lighting up a tooltip for a
 * drop that then does nothing on release.
 */
interface DropPerformer {
    /** Whether this performer would actually do something for [operation] - a pure type check. */
    fun supports(operation: DropOperation): Boolean

    /** Carry out [operation]. Only ever called when [supports] just returned `true` for it. */
    fun perform(operation: DropOperation): Boolean
}

/**
 * [forLogTable] wires the log table's drop operations to the command layer. jj-idea-8fxs (this
 * bead) wires [DropOperation.Rebase] to `jj rebase` with an undo balloon; every other
 * [DropOperation] variant is unwired here and named with the bead that will wire it.
 */
object DropPerformers {
    fun forLogTable(project: Project): DropPerformer = object : DropPerformer {
        override fun supports(operation: DropOperation): Boolean = when (operation) {
            is DropOperation.Rebase -> true
            is DropOperation.Duplicate -> false // jj-idea-p6nb
            is DropOperation.MoveBookmark -> false // jj-idea-ibth
            is DropOperation.MoveTag -> false // jj-idea-vdwh
            is DropOperation.EditWorkingCopy -> false // jj-idea-pk2c
            is DropOperation.Push -> false // jj-idea-ibth
            is DropOperation.SquashFiles -> false // jj-idea-yvry
            is DropOperation.SplitFiles -> false // jj-idea-yvry
        }

        override fun perform(operation: DropOperation): Boolean = when (operation) {
            is DropOperation.Rebase -> {
                val repo = operation.destination.repo
                executeRebase(project, repo, operation.toRebaseSpec())
                true
            }
            is DropOperation.Duplicate -> false // jj-idea-p6nb
            is DropOperation.MoveBookmark -> false // jj-idea-ibth
            is DropOperation.MoveTag -> false // jj-idea-vdwh
            is DropOperation.EditWorkingCopy -> false // jj-idea-pk2c
            is DropOperation.Push -> false // jj-idea-ibth
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
