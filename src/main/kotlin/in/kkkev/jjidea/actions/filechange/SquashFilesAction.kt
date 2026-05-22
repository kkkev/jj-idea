package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.executeSquashInto
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.singleRepoForFiles
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.squash.SquashIntoDialog
import `in`.kkkev.jjidea.ui.squash.SquashMode
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Squash selected files into the parent change, from a file changes context.
 *
 * Works in three contexts:
 * - Working copy panel: resolves WC entry from state model (no LOG_ENTRY)
 * - Commit details panel / historical editor: uses LOG_ENTRY from data context
 * - Project view / current editor: uses working copy
 *
 * Hidden when the entry is immutable or has no parents.
 * Immutable parents are filtered out from the candidate list at action time.
 */
class SquashFilesAction : DumbAwareAction(
    JujutsuBundle.message("action.squash.files"),
    JujutsuBundle.message("action.squash.files.description"),
    JujutsuIcons.Squash
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val entry = resolveEntry(e)
        e.presentation.isEnabledAndVisible = entry != null && !entry.immutable && entry.parentIds.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = resolveEntry(e) ?: return
        val preSelectedFiles = e.filePaths.toSet()

        runInBackground {
            val changes = ChangeService.loadChanges(entry)
            val candidateParents = entry.parentIds.mapNotNull { entry.repo.getLogEntry(it) }
                .filter { !it.immutable }

            runLater {
                val dialog = SquashIntoDialog(
                    project,
                    entry.repo,
                    SquashMode.PickDestination(listOf(entry), candidateParents),
                    changes,
                    preSelectedFiles = preSelectedFiles
                )
                if (dialog.showAndGet()) {
                    dialog.result?.let { executeSquashInto(project, entry.repo, listOf(entry), it) }
                }
            }
        }
    }

    private fun resolveEntry(e: AnActionEvent) = e.logEntry ?: e.singleRepoForFiles?.workingCopy
}
