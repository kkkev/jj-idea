package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RebaseDestinationMode
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.createUndoTrackedCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.duplicate.DuplicateDialog
import `in`.kkkev.jjidea.ui.services.withUndoBalloon

private val duplicateLog = Logger.getInstance("in.kkkev.jjidea.actions.change.duplicateOntoAction")

/**
 * Duplicate-onto action. Opens a dialog to choose a destination and placement, then
 * executes `jj duplicate` with the chosen parameters.
 */
fun duplicateOntoAction(
    project: Project,
    repo: JujutsuRepository?,
    entries: List<LogEntry>
) = nullAndDumbAwareAction(repo, "log.action.duplicate.onto", AllIcons.Actions.Copy) {
    val dialog = DuplicateDialog(project, target, entries)
    if (!dialog.showAndGet()) return@nullAndDumbAwareAction

    val spec = dialog.result ?: return@nullAndDumbAwareAction
    executeDuplicate(project, target, entries.map { it.id }, spec.destinations, spec.destinationMode)
}

/**
 * Runs `jj duplicate` with undo tracking and an undo balloon on success. Shared by
 * [duplicateOntoAction] (dialog path) and [in.kkkev.jjidea.ui.dnd.DropPerformers] (drag-and-drop
 * path, jj-idea-p6nb) - one wiring path for both means the dialog action also gains the undo
 * balloon it didn't have before, same trade already accepted for `executeRebase`
 * (`actions/change/rebaseAction.kt`). Invalidates with no `select` - unlike rebase, `jj duplicate`
 * mints a new change id we don't know until after the command runs.
 */
internal fun executeDuplicate(
    project: Project,
    repo: JujutsuRepository,
    revisions: List<Revision>,
    destinations: List<Revision>,
    mode: RebaseDestinationMode
) {
    repo.createUndoTrackedCommand { duplicate(revisions, destinations, mode) }
        .onSuccess {
            repo.invalidate(vfsChanged = true)
            duplicateLog.info("Duplicated $revisions onto $destinations")
        }
        .onFailure { tellUser(project, "log.action.duplicate.error") }
        .withUndoBalloon(project, repo, "log.action.duplicate.undo")
        .executeAsync()
}
