package `in`.kkkev.jjidea.actions.file

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.singleRepoForFiles
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate

/**
 * Restores selected files to their state in the parent revision (@-).
 * This is an immediate action with no popup - for revision selection use file history "Get".
 *
 * Works in two contexts:
 * - Editor/Project view: uses VIRTUAL_FILE_ARRAY
 * - Changes tree: uses SELECTED_CHANGES
 */
class RestoreSelectionAction : DumbAwareAction(
    JujutsuBundle.message("action.restore.selection"),
    JujutsuBundle.message("action.restore.selection.description"),
    AllIcons.Actions.Rollback
) {
    private val logger = Logger.getInstance(javaClass)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val filePaths = e.filePaths
        val repo = e.singleRepoForFiles ?: return

        // Show confirmation dialog
        val title = if (filePaths.size == 1) {
            JujutsuBundle.message("action.restore.selection.confirm.title", filePaths.first().name)
        } else {
            JujutsuBundle.message("action.restore.selection.confirm.title", "${filePaths.size} files")
        }
        val message = if (filePaths.size == 1) {
            JujutsuBundle.message("action.restore.selection.confirm.single")
        } else {
            JujutsuBundle.message("action.restore.selection.confirm.multiple", filePaths.size)
        }

        if (Messages.showYesNoDialog(project, message, title, Messages.getWarningIcon()) != Messages.YES) {
            return
        }

        repo.commandExecutor.createCommand {
            restore(filePaths, WorkingCopy.parent)
        }
            .onSuccess {
                repo.invalidate(vfsChanged = true)
                logger.info("Restored ${filePaths.size} file(s) to parent revision")
            }
            .onFailure { tellUser(project, "action.restore.selection.error") }
            .executeAsync()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val entry = e.logEntry

        // Hide when in historical context (entry is present and not working copy)
        // In that case, RestoreToChangeAction should be used instead
        val isHistoricalContext = entry != null && !entry.isWorkingCopy
        val hasValidFiles = e.singleRepoForFiles != null

        e.presentation.isEnabledAndVisible = !isHistoricalContext && hasValidFiles
    }
}
