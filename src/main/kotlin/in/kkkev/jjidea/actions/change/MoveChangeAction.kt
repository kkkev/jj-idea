package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.JujutsuDataKeys.LogNeighbours
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.logNeighbours
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RebaseDestinationMode
import `in`.kkkev.jjidea.jj.RebaseSourceMode
import `in`.kkkev.jjidea.ui.rebase.RebaseSpec
import javax.swing.Icon

/**
 * Which way a commit is swapping in the DAG, and the `jj rebase` placement that accomplishes
 * it. Moving *up* swaps the selected commit with its single child (it becomes the child's new
 * parent - `-A`, insert after); moving *down* swaps it with its single parent (it becomes the
 * parent's new parent - `-B`, insert before). See jj-idea-owje's design notes, verified against
 * real jj 0.44: a linear `P -> X -> C`, `jj rebase -r X -A C` produces `P -> C -> X` - the
 * child's own commit id changes too (jj's standard `-r` "hole-filling" reattaches X's old
 * child to X's old parent, independent of `-A`'s own insert semantics), so the immutability
 * guard below must check the destination on *both* directions, not just `-B`'s.
 */
enum class MoveDirection(val destinationMode: RebaseDestinationMode) {
    UP(RebaseDestinationMode.INSERT_AFTER),
    DOWN(RebaseDestinationMode.INSERT_BEFORE)
}

/** A fully-resolved Move Up/Down: the selected commit, the neighbour it swaps with, and how. */
data class MoveTarget(val entry: LogEntry, val neighbour: LogEntry, val direction: MoveDirection)

/**
 * Whether [entry] can move [direction] given its DAG [neighbours], or `null` if not:
 * - the selection must be a single entry (no [entry], or a multi-select with no [LOG_ENTRY][
 *   `in`.kkkev.jjidea.actions.JujutsuDataKeys.LOG_ENTRY], already rules this out upstream)
 * - it must have exactly one child (Up) or exactly one parent (Down) - zero or several is
 *   ambiguous: which one would it swap with?
 * - neither commit may be immutable - confirmed (see [MoveDirection]'s KDoc) that a swap in
 *   either direction rewrites the destination, not just the moving commit
 *
 * No same-repo check: [neighbours] is built from [in.kkkev.jjidea.jj.ChangeKey]-scoped
 * graph edges ([in.kkkev.jjidea.ui.log.buildChildrenIndex] for the child,
 * `entryFor(ChangeKey(entry.repo, parentId))` for the parent - see
 * [in.kkkev.jjidea.ui.log.JujutsuLogTable]'s `selectedNeighbours`), so a resolved neighbour is
 * *structurally* always in the same repository as [entry] - unlike an earlier version of this
 * function that resolved neighbours from display-row adjacency, where two repos' unrelated rows
 * could interleave and needed an explicit guard.
 */
fun moveTarget(entry: LogEntry?, neighbours: LogNeighbours?, direction: MoveDirection): MoveTarget? {
    val e = entry ?: return null
    val n = when (direction) {
        MoveDirection.UP -> neighbours?.singleChild
        MoveDirection.DOWN -> neighbours?.singleParent
    } ?: return null
    if (e.immutable || n.immutable) return null
    return MoveTarget(e, n, direction)
}

/**
 * Toolbar/context-menu "Move Up"/"Move Down" actions (jj-idea-owje, GitHub #93): swap the
 * selected commit with its single child (Up) or single parent (Down) in the commit graph, as a
 * keyboard-first complement to drag-and-drop reordering (jj-idea-6oeg's preview-gated
 * follow-ups). Each is just
 * `jj rebase -r <selected> -A/-B <neighbour>` - the same `-A`/`-B` placement the "New Change..."
 * dialog (GitHub #83) already exercises via [in.kkkev.jjidea.jj.CommandExecutor.rebase] - run
 * through the existing [executeRebase] (undo tracking, undo balloon, refresh, error reporting)
 * shared with the Rebase dialog and drag-and-drop.
 *
 * Reads its target from the log table's live selection ([in.kkkev.jjidea.actions.logEntry]/
 * [in.kkkev.jjidea.actions.logNeighbours]), so - like [RebaseChangeAction] and [EditChangeAction]
 * - it disables automatically outside the log table (no [LOG_ENTRIES] neighbour data) and can be
 * registered once and referenced by ID from both the log toolbar and its context menu.
 */
sealed class MoveChangeAction(
    private val direction: MoveDirection,
    messageKey: String,
    icon: Icon
) : DumbAwareAction(
        JujutsuBundle.message(messageKey),
        JujutsuBundle.message("$messageKey.tooltip"),
        icon
    ) {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = moveTarget(e.logEntry, e.logNeighbours, direction) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = moveTarget(e.logEntry, e.logNeighbours, direction) ?: return
        val spec = RebaseSpec(
            revisions = listOf(target.entry.id),
            destinations = listOf(target.neighbour.id),
            sourceMode = RebaseSourceMode.REVISION,
            destinationMode = direction.destinationMode
        )
        log.info("Moving ${target.entry.id} $direction relative to ${target.neighbour.id}")
        executeRebase(project, target.entry.repo, spec, undoLabelKey = "log.action.move.undo")
    }
}

class MoveChangeUpAction : MoveChangeAction(MoveDirection.UP, "log.action.move.up", AllIcons.Actions.MoveUp)

class MoveChangeDownAction : MoveChangeAction(MoveDirection.DOWN, "log.action.move.down", AllIcons.Actions.MoveDown)
