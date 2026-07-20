package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate

/**
 * Duplicate change action. Creates identical copies of the selected change(s) in place
 * (same location as the originals) via `jj duplicate`.
 */
fun duplicateChangeAction(
    project: Project,
    repo: JujutsuRepository?,
    entries: List<LogEntry>
) = nullAndDumbAwareAction(repo, "log.action.duplicate", AllIcons.Actions.Copy) {
    val revisions = entries.map { it.id }
    target.commandExecutor
        .createCommand { duplicate(revisions) }
        .onSuccess {
            target.invalidate()
            log.info("Duplicated $revisions")
        }
        .onFailure { tellUser(project, "log.action.duplicate.error") }
        .executeAsync()
}
