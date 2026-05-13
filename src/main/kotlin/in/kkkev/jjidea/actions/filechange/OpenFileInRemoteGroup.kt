package `in`.kkkev.jjidea.actions.filechange

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.git.ClassifiedRemote
import `in`.kkkev.jjidea.actions.git.RemoteUrlBuilder
import `in`.kkkev.jjidea.actions.git.applyRemoteVisibility
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.util.runInBackground

/**
 * Opens the selected file at the current historical commit in GitHub or GitLab.
 *
 * - 0 recognised remotes → hidden
 * - 1 recognised remote → transparent (child shown inline as "Open File in GitHub 'origin'" with icon)
 * - 2+ recognised remotes → "Open File on Remote" submenu
 *
 * Hidden when no pushed ancestor exists or no file with afterRevision is selected.
 * For non-pushed commits (including working copy), resolves the nearest pushed ancestor per remote so the URL is valid.
 */
class OpenFileInRemoteGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun classifiedRemotes(entry: LogEntry) =
        RemoteUrlBuilder.classifiedRemotes(entry.repo.gitRemotes)

    override fun update(e: AnActionEvent) {
        val entry = e.logEntry
        if (entry == null || !entry.hasPushedAncestor || e.changes.none { it.after != null }) {
            e.presentation.isVisible = false
            return
        }
        applyRemoteVisibility(e, classifiedRemotes(entry).size, JujutsuBundle.message("action.open.file.in.remote"))
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val entry = e?.logEntry ?: return emptyArray()
        val changes = e.changes.takeUnless { it.isEmpty() } ?: return emptyArray()
        val filePaths = changes.mapNotNull { it.after?.filePath }.takeUnless { it.isEmpty() } ?: return emptyArray()
        val relativePaths = filePaths.map { filePath -> entry.repo.getRelativePath(filePath) }
        return classifiedRemotes(entry).map { remote ->
            fileChangeRemoteAction(entry, remote, relativePaths)
        }.toTypedArray()
    }
}

private fun fileChangeRemoteAction(
    entry: LogEntry,
    remote: ClassifiedRemote,
    relativePaths: List<String>
) = object : DumbAwareAction(
    JujutsuBundle.message("log.action.open.file.in.remote.named", remote.kind.label, remote.name),
    JujutsuBundle.message("log.action.open.file.in.remote.tooltip", remote.kind.label),
    remote.kind.icon
) {
    override fun actionPerformed(e: AnActionEvent) {
        runInBackground {
            val commitHash = if (entry.immutable) {
                entry.commitId.full
            } else {
                entry.repo.commandExecutor.latestPushedAncestorCommitId(entry.id, remote.name)
            } ?: return@runInBackground
            relativePaths.forEach { relativePath ->
                BrowserUtil.browse(RemoteUrlBuilder.fileUrl(remote.base, remote.kind, commitHash, relativePath))
            }
        }
    }
}
