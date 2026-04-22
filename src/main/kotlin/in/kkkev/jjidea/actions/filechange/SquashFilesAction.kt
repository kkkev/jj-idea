package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.executeSquash
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.singleRepoForFiles
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.squash.SquashDialog
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Squash selected files into the parent change, from a file changes context.
 *
 * Works in three contexts:
 * - Working copy panel: resolves WC entry from state model (no LOG_ENTRY)
 * - Commit details panel / historical editor: uses LOG_ENTRY from data context
 * - Project view / current editor: uses working copy
 *
 * Hidden when the entry is immutable or has != 1 parent.
 * Parent immutability is checked at action time when parent data is loaded.
 */
class SquashFilesAction : DumbAwareAction(
    JujutsuBundle.message("action.squash.files"),
    JujutsuBundle.message("action.squash.files.description"),
    JujutsuIcons.Squash
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val entry = resolveEntry(e)
        e.presentation.isEnabledAndVisible = entry != null && !entry.immutable && entry.parentIds.size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = resolveEntry(e) ?: return
        val preSelectedFiles = e.filePaths.toSet()

        runInBackground {
            val changes = ChangeService.loadChanges(entry)
            // TODO What if this is a merge? See jj-idea-25t7
            val parentEntry = entry.parentIds.firstOrNull()?.let {
                entry.repo.getLogEntry(it)
            }

            runLater {
                if (parentEntry?.immutable == true) {
                    Messages.showWarningDialog(
                        project,
                        JujutsuBundle.message("action.squash.files.error.message", "Parent change is immutable"),
                        JujutsuBundle.message("action.squash.files.error.title")
                    )
                    return@runLater
                }

                val dialog = SquashDialog(project, entry, parentEntry, changes, preSelectedFiles)
                if (dialog.showAndGet()) {
                    dialog.result?.let { executeSquash(project, entry, parentEntry, it, "action.squash.files.error") }
                }
            }
        }
    }

    private fun resolveEntry(e: AnActionEvent) = e.logEntry ?: e.singleRepoForFiles?.workingCopy
}
