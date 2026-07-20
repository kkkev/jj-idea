package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.duplicate.DuplicateDialog

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
    val revisions = entries.map { it.id }
    target.commandExecutor
        .createCommand { duplicate(revisions, spec.destinations, spec.destinationMode) }
        .onSuccess {
            target.invalidate()
            log.info("Duplicated $revisions onto ${spec.destinations}")
        }
        .onFailure { tellUser(project, "log.action.duplicate.error") }
        .executeAsync()
}
