package `in`.kkkev.jjidea.actions.filechange

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.actions.logEntryForFile
import `in`.kkkev.jjidea.actions.restorePaths
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.restore.DROP_WORDING
import `in`.kkkev.jjidea.ui.restore.performRestore
import `in`.kkkev.jjidea.vcs.filePath

/**
 * Drops the selected file(s)' changes from a historical revision (GitHub #148):
 * `jj restore --changes-in <rev> <paths>`, i.e. `--from <rev>- --into <rev>`, so the files go
 * back to their content in the revision's parent(s) and descendants are rebased. One command for
 * all selected files, so one Undo reverts the whole drop.
 *
 * Contrast [RestoreToChangeAction], which copies the revision's content *into the working copy*.
 *
 * Hidden when there is no historical, mutable entry (working copy: use RestoreSelectionAction).
 * Visible but disabled when the details panel shows several commits, since the selected files
 * could belong to any of them and [in.kkkev.jjidea.actions.JujutsuDataKeys.LOG_ENTRY] is only the first.
 */
class DropChangesAction : DumbAwareAction(
    JujutsuBundle.message("action.drop.changes"),
    JujutsuBundle.message("action.drop.changes.description"),
    AllIcons.Actions.Rollback
) {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val entry = e.logEntryForFile ?: return
        if ((e.getData(JujutsuDataKeys.DETAILS_ENTRIES)?.size ?: 1) > 1) return
        val filePaths = (e.restorePaths.takeUnless { it.isEmpty() } ?: e.file?.let { listOf(it.filePath) }) ?: return

        val repo = entry.repo

        performRestore(
            repo = repo,
            restore = { restoreChangesIn(it, entry.id) },
            targetLabel = entry.id.short,
            preSelected = filePaths.toSet(),
            errorMessageKey = "action.drop.changes.error",
            undoLabelKey = "action.drop.changes.undo",
            loadChanges = { ChangeService.loadChanges(entry) },
            wording = DROP_WORDING
        ) { dropped ->
            // Descendants (including @) are rebased, so local files can change.
            repo.invalidate(vfsChanged = true)
            log.info("Dropped ${dropped.joinToString { it.name }} from revision ${entry.id.short}")
        }
    }

    override fun update(e: AnActionEvent) {
        val entry = e.logEntryForFile
        val hasContent = e.changes.isNotEmpty() || e.file != null
        val visible = entry != null && !entry.isWorkingCopy && !entry.immutable && hasContent
        e.presentation.isVisible = e.project != null && visible

        val multiCommit = (e.getData(JujutsuDataKeys.DETAILS_ENTRIES)?.size ?: 1) > 1
        e.presentation.isEnabled = e.presentation.isVisible && !multiCommit
        if (e.presentation.isVisible && multiCommit) {
            e.presentation.description = JujutsuBundle.message("action.drop.changes.multiple.commits")
        }
    }
}
