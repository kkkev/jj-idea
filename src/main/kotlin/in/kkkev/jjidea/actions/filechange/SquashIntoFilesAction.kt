package `in`.kkkev.jjidea.actions.filechange

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.executeSquashInto
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.singleRepoForFiles
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.squash.SquashIntoDialog
import `in`.kkkev.jjidea.ui.squash.SquashMode
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Squash selected files into an arbitrary destination change, from a file changes context.
 *
 * Works in the same contexts as [SquashFilesAction] (working copy panel, commit details,
 * project view, editor) but opens the destination-picker dialog instead of squashing into
 * the parent. Enabled for any mutable change, including merge commits.
 */
class SquashIntoFilesAction : DumbAwareAction(
    JujutsuBundle.message("action.squash.into.files"),
    JujutsuBundle.message("action.squash.into.files.description"),
    JujutsuIcons.Squash
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val entry = resolveEntry(e)
        e.presentation.isEnabledAndVisible = entry != null && !entry.immutable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = resolveEntry(e) ?: return
        val preSelectedFiles = e.filePaths.toSet()
        val repo = entry.repo
        val settings = JujutsuSettings.getInstance(project)

        runInBackground {
            val changes = ChangeService.loadChanges(entry)
            val revsetSetting = settings.logRevset(repo)
            val revset: Revset = if (revsetSetting.isBlank()) Revset.Default else Expression(revsetSetting)
            val allEntries = repo.logService.getLog(revset, limit = settings.logChangeLimit(repo))
                .getOrElse { emptyList() }

            runLater {
                val dialog = SquashIntoDialog(
                    project,
                    repo,
                    SquashMode.PickDestination(listOf(entry)),
                    changes,
                    allEntries,
                    preSelectedFiles
                )
                if (dialog.showAndGet()) {
                    dialog.result?.let { executeSquashInto(project, repo, listOf(entry), it) }
                }
            }
        }
    }

    private fun resolveEntry(e: AnActionEvent) = e.logEntry ?: e.singleRepoForFiles?.workingCopy
}
