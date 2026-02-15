package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.singleJujutsuRepository

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
        val files = e.files
        if (files.isEmpty()) return

        val repo = files.singleJujutsuRepository ?: return

        // Show confirmation dialog
        val title = if (files.size == 1) {
            JujutsuBundle.message("action.restore.selection.confirm.title", files.first().name)
        } else {
            JujutsuBundle.message("action.restore.selection.confirm.title", "${files.size} files")
        }
        val message = if (files.size == 1) {
            JujutsuBundle.message("action.restore.selection.confirm.single")
        } else {
            JujutsuBundle.message("action.restore.selection.confirm.multiple", files.size)
        }

        if (Messages.showYesNoDialog(project, message, title, Messages.getWarningIcon()) != Messages.YES) {
            return
        }

        repo.commandExecutor.createCommand {
            restore(files.map { it.filePath }, WorkingCopy.parent)
        }
            .onSuccess {
                VfsUtil.markDirtyAndRefresh(false, false, true, *files.toTypedArray())
                repo.invalidate()
                logger.info("Restored ${files.size} file(s) to parent revision")
            }
            .onFailureTellUser("action.restore.selection.error", project, logger)
            .executeAsync()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = e.files
        val entry = e.logEntry

        // Hide when in historical context (entry is present and not working copy)
        // In that case, RestoreToChangeAction should be used instead
        val isHistoricalContext = entry != null && !entry.isWorkingCopy
        val hasValidFiles = e.project != null && files.isNotEmpty() && files.singleJujutsuRepository != null

        e.presentation.isEnabledAndVisible = !isHistoricalContext && hasValidFiles
    }
}
