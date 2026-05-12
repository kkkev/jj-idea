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
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.logEntryForFile
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.vcs.filePath

/**
 * Restores selected file(s) to their state in a historical revision.
 * Uses [in.kkkev.jjidea.actions.JujutsuDataKeys.LOG_ENTRY] to determine the revision.
 *
 * Works in both changes tree context (LOG_ENTRY DataSink) and editor context (file user data).
 *
 * Hidden when:
 * - No log entry resolvable (working copy editor context)
 * - Log entry isWorkingCopy is true (use RestoreSelectionAction instead)
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
        val entry = e.logEntryForFile ?: return
        val filePaths = (e.filePaths.takeUnless { it.isEmpty() } ?: e.file?.let { listOf(it.filePath) }) ?: return

        val fileNames = filePaths.joinToString { it.name }
        val changeId = entry.id
        val repo = entry.repo

        // Show confirmation dialog
        val (title, message) = if (filePaths.size == 1) {
            JujutsuBundle.message(
                "action.restore.to.revision.confirm.title",
                fileNames.first(),
                changeId.short
            ) to JujutsuBundle.message(
                "action.restore.to.revision.confirm.message",
                changeId.short
            )
        } else {
            JujutsuBundle.message(
                "action.restore.to.revision.confirm.title.multiple",
                filePaths.size,
                changeId.short
            ) to JujutsuBundle.message(
                "action.restore.to.revision.confirm.message.multiple",
                filePaths.size,
                changeId.short
            )
        }
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
        val entry = e.logEntryForFile
        // Changes tree: has changes; editor context: has a file
        val hasContent = e.changes.isNotEmpty() || e.file != null

        // Hide when no entry, entry is working copy, or no file content to act on
        val visible = entry != null && !entry.isWorkingCopy && hasContent
        e.presentation.isEnabledAndVisible = e.project != null && visible
    }
}
