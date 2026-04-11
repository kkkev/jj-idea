package `in`.kkkev.jjidea.actions.file

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.actions.git.ClassifiedRemote
import `in`.kkkev.jjidea.actions.git.RemoteUrlBuilder
import `in`.kkkev.jjidea.actions.git.applyRemoteVisibility
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground

/**
 * Action group that opens the current editor file at its latest pushed ancestor commit
 * in GitHub or GitLab. Registered in [RevealGroup] ("Open In" submenu).
 *
 * - 0 recognized remotes → hidden
 * - 1 recognized remote → transparent (child shown inline as "Open in GitHub 'origin'" with icon)
 * - 2+ recognized remotes → "Open on Remote" submenu
 *
 * Remote list is fetched lazily from [JujutsuRepository.gitRemotes] (cached per-repo for the session).
 * The commit hash lookup is deferred to [actionPerformed].
 */
class OpenInRemoteFromEditorGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val repo = e?.repoForFile ?: return emptyArray()
        return RemoteUrlBuilder.classifiedRemotes(repo.gitRemotes).map { remote ->
            editorRemoteAction(repo, remote)
        }.toTypedArray()
    }

    override fun update(e: AnActionEvent) {
        val repo = e.repoForFile ?: run {
            e.presentation.isVisible = false
            return
        }
        applyRemoteVisibility(e, RemoteUrlBuilder.classifiedRemotes(repo.gitRemotes).size)
    }
}

private fun editorRemoteAction(
    repo: JujutsuRepository,
    remote: ClassifiedRemote
) = object : DumbAwareAction(
    JujutsuBundle.message("log.action.open.in.remote.named", remote.kind.label, remote.name),
    JujutsuBundle.message("action.open.file.in.remote.from.editor.description"),
    remote.kind.icon
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.file ?: return
        val relativePath = repo.getRelativePath(file)
        runInBackground {
            val commitHash = repo.commandExecutor.latestPushedAncestorCommitId(remote.name)
            if (commitHash == null) {
                JujutsuNotifications.notify(
                    project,
                    JujutsuBundle.message("action.open.file.in.remote.no.pushed.title"),
                    JujutsuBundle.message("action.open.file.in.remote.no.pushed", remote.name),
                    NotificationType.INFORMATION
                )
                return@runInBackground
            }
            BrowserUtil.browse(RemoteUrlBuilder.fileUrl(remote.base, remote.kind, commitHash, relativePath))
        }
    }
}
