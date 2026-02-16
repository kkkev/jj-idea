package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.rebase.RebaseDialog

/**
 * Rebase action. Opens a dialog to configure source mode, destination, and placement,
 * then executes `jj rebase` with the chosen parameters.
 */
fun rebaseAction(
    project: Project,
    repo: JujutsuRepository?,
    entries: List<LogEntry>,
    allEntries: List<LogEntry> = emptyList()
) =
    nullAndDumbAwareAction(repo, "log.action.rebase", AllIcons.Vcs.Merge) {
        val dialog = RebaseDialog(project, target, entries, allEntries)
        if (!dialog.showAndGet()) return@nullAndDumbAwareAction

        val spec = dialog.result ?: return@nullAndDumbAwareAction
        target.commandExecutor
            .createCommand { rebase(spec.revisions, spec.destinations, spec.sourceMode, spec.destinationMode) }
            .onSuccess {
                target.invalidate(select = spec.revisions.first())
                log.info("Rebased ${spec.revisions} onto ${spec.destinations}")
            }
            .onFailureTellUser("log.action.rebase.error", project, log)
            .executeAsync()
    }
