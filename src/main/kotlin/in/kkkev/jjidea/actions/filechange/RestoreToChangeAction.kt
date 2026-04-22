package `in`.kkkev.jjidea.actions.filechange

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VfsUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.invalidate

/**
 * Restores selected file(s) to their state in a historical revision.
 * Uses [in.kkkev.jjidea.actions.JujutsuDataKeys.LOG_ENTRY] to determine the revision.
 *
 * This action is for our custom log panels where we have a LogEntry context.
 *
 * Hidden when:
 * - No LOG_ENTRY is present (working copy context)
 * - LOG_ENTRY.isWorkingCopy is true (use RestoreSelectionAction instead)
 */
class RestoreToChangeAction : DumbAwareAction(
    JujutsuBundle.message("action.restore.to.revision"),
    JujutsuBundle.message("action.restore.to.revision.description"),
    AllIcons.Actions.Rollback
) {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = e.logEntry ?: return
        val filePaths = e.filePaths.takeUnless { it.isEmpty() } ?: return

        val fileNames = filePaths.joinToString { it.name }
        val changeId = entry.id
        val repo = entry.repo

        // Show confirmation dialog
        val title = JujutsuBundle.message("action.restore.to.revision.confirm.title", fileNames, changeId.short)
        val message = JujutsuBundle.message("action.restore.to.revision.confirm.message", changeId.short)
        if (Messages.showYesNoDialog(project, message, title, Messages.getWarningIcon()) != Messages.YES) {
            return
        }

        repo.commandExecutor.createCommand {
            restore(filePaths, changeId)
        }
            .onSuccess {
                val files = filePaths.map(FilePath::getVirtualFile)
                VfsUtil.markDirtyAndRefresh(false, false, true, *files.toTypedArray())
                repo.invalidate()
                log.info("Restored $fileNames to revision ${changeId.short}")
            }
            .onFailure { tellUser(project, "action.restore.to.revision.error") }
            .executeAsync()
    }

    override fun update(e: AnActionEvent) {
        val entry = e.logEntry
        val hasChange = e.changes.isNotEmpty()

        // Hide when no entry, entry is working copy, or no change selected
        val visible = entry != null && !entry.isWorkingCopy && hasChange
        e.presentation.isEnabledAndVisible = e.project != null && visible
    }
}
