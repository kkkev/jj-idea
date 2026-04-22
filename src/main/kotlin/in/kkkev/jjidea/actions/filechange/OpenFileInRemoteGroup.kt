package `in`.kkkev.jjidea.actions.filechange

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.git.RecognizedRemote
import `in`.kkkev.jjidea.actions.git.RemoteUrlBuilder
import `in`.kkkev.jjidea.actions.git.applyRemoteVisibility
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.LogEntry

/**
 * Opens the selected file at the current historical commit in GitHub or GitLab.
 *
 * - 0 recognized remotes → hidden
 * - 1 recognized remote → transparent (child shown inline as "Open File in GitHub 'origin'" with icon)
 * - 2+ recognized remotes → "Open File on Remote" submenu
 *
 * Hidden in working copy context or when no single file with afterRevision is selected.
 * Uses [LogEntry.immutable] as a heuristic for whether the commit is pushed.
 */
class OpenFileInRemoteGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun remotes(entry: LogEntry) =
        RemoteUrlBuilder.recognizedRemotes(entry.repo.gitRemotes, entry.commitId.full, entry.immutable)

    override fun update(e: AnActionEvent) {
        val entry = e.logEntry
        if (entry == null || entry.isWorkingCopy || e.changes.none { it.after != null }) {
            e.presentation.isVisible = false
            return
        }
        applyRemoteVisibility(e, remotes(entry).size, JujutsuBundle.message("action.open.file.in.remote"))
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val entry = e?.logEntry ?: return emptyArray()
        val changes = e.changes.takeUnless { it.isEmpty() } ?: return emptyArray()
        val filePaths = changes.mapNotNull { it.after?.filePath }.takeUnless { it.isEmpty() } ?: return emptyArray()
        val relativePaths = filePaths.map { filePath -> entry.repo.getRelativePath(filePath) }
        val commitHash = entry.commitId.full
        return remotes(entry).map { remote ->
            fileChangeRemoteAction(remote, commitHash, relativePaths)
        }.toTypedArray()
    }
}

private fun fileChangeRemoteAction(remote: RecognizedRemote, commitHash: String, relativePaths: List<String>) =
    object : DumbAwareAction(
        JujutsuBundle.message("log.action.open.file.in.remote.named", remote.kind.label, remote.name),
        if (remote.isPushed) {
            JujutsuBundle.message("log.action.open.file.in.remote.tooltip", remote.kind.label)
        } else {
            JujutsuBundle.message("log.action.open.file.in.remote.not.pushed.tooltip", remote.kind.label)
        },
        remote.kind.icon
    ) {
        override fun actionPerformed(e: AnActionEvent) = relativePaths.forEach { relativePath ->
            BrowserUtil.browse(RemoteUrlBuilder.fileUrl(remote.base, remote.kind, commitHash, relativePath))
        }
    }
