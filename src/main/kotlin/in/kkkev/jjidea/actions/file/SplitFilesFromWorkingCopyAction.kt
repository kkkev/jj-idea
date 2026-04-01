package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.executeSplit
import `in`.kkkev.jjidea.actions.files
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.split.SplitDialog
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.singleJujutsuRepository

/**
 * Split selected files into a new change, from the project view or editor context.
 * Always operates on the working copy.
 */
class SplitFilesFromWorkingCopyAction : DumbAwareAction(
    JujutsuBundle.message("action.split.files"),
    JujutsuBundle.message("action.split.files.description"),
    JujutsuIcons.Split
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.files
        if (project == null || files.isEmpty()) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val repo = files.singleJujutsuRepository
        if (repo == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val entry = project.stateModel.repositoryStates.value
            .find { it.isWorkingCopy && it.repo == repo }
        e.presentation.isEnabledAndVisible = entry != null && !entry.immutable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.files
        val repo = files.singleJujutsuRepository ?: return
        val entry = project.stateModel.repositoryStates.value
            .find { it.isWorkingCopy && it.repo == repo } ?: return
        val preSelectedFiles = files.map { it.filePath }.toSet()

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
}
