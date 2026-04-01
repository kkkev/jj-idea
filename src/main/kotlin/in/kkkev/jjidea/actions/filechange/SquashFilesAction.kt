package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.executeSquash
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.files
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.squash.SquashDialog
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.singleJujutsuRepository

/**
 * Squash selected files into the parent change, from a file changes context.
 *
 * Works in two contexts:
 * - Working copy panel: resolves WC entry from state model (no LOG_ENTRY)
 * - Commit details panel: uses LOG_ENTRY from data context
 *
 * Hidden when the entry is immutable or has != 1 parent.
 * Parent immutability is checked at action time when parent data is loaded.
 */
class SquashFilesAction : DumbAwareAction(
    JujutsuBundle.message("action.squash.files"),
    JujutsuBundle.message("action.squash.files.description"),
    JujutsuIcons.Squash
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || e.changes.isEmpty()) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val entry = resolveEntry(e)
        e.presentation.isEnabledAndVisible = entry != null && !entry.immutable && entry.parentIds.size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = resolveEntry(e) ?: return
        val preSelectedFiles = e.changes.mapNotNull { it.filePath }.toSet()

        runInBackground {
            val changes = ChangeService.loadChanges(entry)
            val parentId = entry.parentIds.firstOrNull()
            val parentEntry = parentId?.let {
                entry.repo.logService.getLogBasic(it).getOrNull()?.firstOrNull()
            }

            runLater {
                if (parentEntry?.immutable == true) {
                    Messages.showWarningDialog(
                        project,
                        JujutsuBundle.message("action.squash.files.error.message", "Parent change is immutable"),
                        JujutsuBundle.message("action.squash.files.error.title")
                    )
                    return@runLater
                }

                val dialog = SquashDialog(project, entry, parentEntry, changes, preSelectedFiles)
                if (dialog.showAndGet()) {
                    dialog.result?.let { executeSquash(project, entry, parentEntry, it, "action.squash.files.error") }
                }
            }
        }
    }

    private fun resolveEntry(e: AnActionEvent): LogEntry? {
        e.logEntry?.let { return it }
        val project = e.project ?: return null
        val repo = e.files.singleJujutsuRepository ?: return null
        return project.stateModel.repositoryStates.value
            .find { it.isWorkingCopy && it.repo == repo }
    }
}
