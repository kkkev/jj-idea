package `in`.kkkev.jjidea.actions.file

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.logEntryForFile
import `in`.kkkev.jjidea.actions.restorePaths
import `in`.kkkev.jjidea.actions.singleRepoForRestore
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.restore.performRestore

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
        val preSelected = e.restorePaths.toSet()
        val repo = e.singleRepoForRestore ?: return

        performRestore(
            repo = repo,
            revision = WorkingCopy.parent,
            targetLabel = JujutsuBundle.message("dialog.restore.target.parent"),
            preSelected = preSelected,
            errorMessageKey = "action.restore.selection.error",
            undoLabelKey = "action.restore.selection.undo",
            loadChanges = { ChangeService.loadChanges(repo.workingCopy) }
        ) { restored ->
            repo.invalidate(vfsChanged = true)
            logger.info("Restored ${restored.size} file(s) to parent revision")
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val entry = e.logEntryForFile

        // Hide when in historical context (entry is present and not working copy)
        // In that case, RestoreToChangeAction should be used instead
        val isHistoricalContext = entry != null && !entry.isWorkingCopy
        e.presentation.isEnabledAndVisible = !isHistoricalContext && e.singleRepoForRestore != null
    }
}
