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
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.restore.performRestore

/**
 * Opens [in.kkkev.jjidea.ui.restore.RestoreDialog] pre-checked with the current selection,
 * restoring the ticked files to their state in the parent revision(s) of @ - for revision
 * selection use file history "Get".
 *
 * Works in three contexts:
 * - Editor/Project view: uses VIRTUAL_FILE_ARRAY
 * - Changes tree: uses SELECTED_CHANGES
 * - Working Copy toolbar (GitHub #84) with nothing selected in the tree: falls back to the
 *   panel's bound repository via [repoFor], opening the dialog with nothing pre-checked so
 *   the button stays usable (rather than greyed out) when the tree selection is empty
 */
class RestoreSelectionAction : DumbAwareAction(
    JujutsuBundle.message("action.restore.selection"),
    JujutsuBundle.message("action.restore.selection.description"),
    AllIcons.Actions.Rollback
) {
    private val logger = Logger.getInstance(javaClass)

    /**
     * The repo to restore in: the selection's repo when one or more files are selected, or - for
     * an empty selection - the working-copy repo behind the current LOG_ENTRY (e.g. the Working
     * Copy toolbar button with nothing ticked in the tree). Historical LOG_ENTRYs are excluded by
     * [update] hiding the action entirely, so this never needs to distinguish them.
     */
    private fun repoFor(e: AnActionEvent): JujutsuRepository? =
        e.singleRepoForRestore ?: e.restorePaths.takeIf { it.isEmpty() }?.let { e.logEntryForFile?.repo }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val preSelected = e.restorePaths.toSet()
        val repo = repoFor(e) ?: return

        performRestore(
            repo = repo,
            restore = { restoreChangesIn(it, WorkingCopy) },
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
        e.presentation.isEnabledAndVisible = !isHistoricalContext && repoFor(e) != null
    }
}
