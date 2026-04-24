package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.executeSplit
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.singleRepoForFiles
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.split.SplitDialog
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Split selected files into a new change, from a file changes context.
 *
 * Works in three contexts:
 * - Working copy panel: resolves WC entry from state model (no LOG_ENTRY)
 * - Commit details panel / historical editor: uses LOG_ENTRY from data context
 * - Project view / current editor: resolves WC entry from state model (no LOG_ENTRY)
 *
 * Hidden when the entry is immutable.
 */
class SplitFilesAction : DumbAwareAction(
    JujutsuBundle.message("action.split.files"),
    JujutsuBundle.message("action.split.files.description"),
    JujutsuIcons.Split
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val entry = resolveEntry(e)
        e.presentation.isEnabledAndVisible = entry?.immutable == false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = resolveEntry(e) ?: return
        val preSelectedFiles = e.filePaths.toSet()

        runInBackground {
            val changes = ChangeService.loadChanges(entry)

            runLater {
                val dialog = SplitDialog(project, entry, changes, preSelectedFiles = preSelectedFiles)
                if (dialog.showAndGet()) {
                    dialog.result?.let { executeSplit(project, entry, it) }
                }
            }
        }
    }

    private fun resolveEntry(e: AnActionEvent) = e.logEntry ?: e.singleRepoForFiles?.workingCopy
}
