package `in`.kkkev.jjidea.actions.filechange

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.executeCommit
import `in`.kkkev.jjidea.actions.file.disableIfOnlyUntrackedIgnored
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.runRecoverableInBackground
import `in`.kkkev.jjidea.ui.commit.CommitDialog
import `in`.kkkev.jjidea.util.runLater

/** Resolves the [LogEntry] a file-change commit action should operate on: only ever the working copy. */
internal fun resolveCommitEntry(e: AnActionEvent): LogEntry? =
    resolveSplitEntry(e)?.takeIf { it.isWorkingCopy }

/**
 * Loads changes on a background thread, then opens [CommitDialog] with the right-clicked
 * [selectedFiles] pre-ticked. Ticking means "is committed".
 */
internal fun performFileCommit(project: Project, entry: LogEntry, selectedFiles: Set<FilePath>) {
    entry.repo.runRecoverableInBackground(retry = { performFileCommit(project, entry, selectedFiles) }) {
        val changes = ChangeService.loadChanges(entry)

        runLater {
            val dialog = CommitDialog(project, entry, changes, preSelectedFiles = selectedFiles)
            if (dialog.showAndGet()) {
                dialog.result?.let { executeCommit(project, entry, it) }
            }
        }
    }
}

/**
 * Commit selected files (`jj commit`): they stay in the working-copy change, which gets a
 * description, and everything else moves to a new working-copy change on top (GitHub #157).
 *
 * Same contexts as [SplitFilesAction], but only visible for the working copy - `jj commit` has
 * no `-r`.
 */
class CommitFilesAction : DumbAwareAction(
    JujutsuBundle.message("action.commit.files"),
    JujutsuBundle.message("action.commit.files.description"),
    AllIcons.Actions.Commit
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val entry = resolveCommitEntry(e)
        e.presentation.isEnabledAndVisible = entry != null
        e.disableIfOnlyUntrackedIgnored()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = resolveCommitEntry(e) ?: return
        performFileCommit(project, entry, e.filePaths.toSet())
    }
}
