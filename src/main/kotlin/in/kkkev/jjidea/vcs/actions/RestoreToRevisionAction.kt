package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.vcs.log.VcsLogDataKeys
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.isJujutsu
import `in`.kkkev.jjidea.vcs.jujutsuRepository

/**
 * Action to restore a file to its state at a specific revision from VCS Log/History views.
 * Works in the changes browser popup menu of file history and VCS log.
 */
class RestoreToRevisionAction : DumbAwareAction(
    JujutsuBundle.message("action.restore.to.revision"),
    JujutsuBundle.message("action.restore.to.revision.description"),
    AllIcons.Actions.Rollback
) {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val change = e.changes.firstOrNull() ?: return

        // Get the revision from VCS Log selection
        val commitSelection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)
        val selectedCommit = commitSelection?.commits?.firstOrNull()

        if (selectedCommit == null) {
            log.warn("No commit selected in VCS Log")
            return
        }

        // Get file path from the change
        val filePath = change.afterRevision?.file ?: change.beforeRevision?.file ?: return
        val fileName = filePath.name

        // Convert hash to ChangeId
        val changeId = ChangeId.fromHexString(selectedCommit.hash.asString())

        // Get repository from file
        val repo = filePath.jujutsuRepository

        // Show confirmation dialog
        val title = JujutsuBundle.message("action.restore.to.revision.confirm.title", fileName, changeId.short)
        val message = JujutsuBundle.message("action.restore.to.revision.confirm.message", changeId.short)
        if (Messages.showYesNoDialog(project, message, title, Messages.getWarningIcon()) != Messages.YES) {
            return
        }

        repo.commandExecutor.createCommand {
            restore(listOf(repo.getRelativePath(filePath)), changeId)
        }
            .onSuccess {
                filePath.virtualFile?.let { vf ->
                    VfsUtil.markDirtyAndRefresh(false, false, true, vf)
                }
                repo.invalidate()
                log.info("Restored $fileName to revision ${changeId.short}")
            }
            .onFailureTellUser("action.restore.to.revision.error", project, log)
            .executeAsync()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val changes = e.getData(VcsDataKeys.SELECTED_CHANGES)
        val commitSelection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)

        // Enable only if we have a project, a selected change, and a commit selection
        val enabled = project != null &&
            changes?.isNotEmpty() == true &&
            commitSelection?.commits?.isNotEmpty() == true &&
            changes.first().filePath?.isJujutsu == true

        e.presentation.isEnabledAndVisible = enabled
    }

}
