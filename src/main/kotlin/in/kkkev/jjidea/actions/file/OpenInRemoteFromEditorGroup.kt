package `in`.kkkev.jjidea.actions.file

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.actions.JujutsuDataKeys.DiffContentInfo
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.actions.git.ClassifiedRemote
import `in`.kkkev.jjidea.actions.git.RemoteUrlBuilder
import `in`.kkkev.jjidea.actions.git.applyRemoteVisibility
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.vcs.possibleLogEntryFor

/**
 * Action group that opens the current editor file at its latest pushed ancestor commit
 * in GitHub or GitLab. Registered in [RevealGroup] ("Open In" submenu) and [Diff.EditorPopupMenu].
 *
 * - 0 recognized remotes → hidden
 * - 1 recognized remote → transparent (child shown inline as "Open in GitHub 'origin'" with icon)
 * - 2+ recognized remotes → "Open on Remote" submenu
 *
 * Remote list is fetched lazily from [JujutsuRepository.gitRemotes] (cached per-repo for the session).
 * The commit hash lookup is deferred to [actionPerformed].
 *
 * ## Diff editor support
 *
 * When shown in a diff viewer popup ([Diff.EditorPopupMenu]), the action also checks
 * [DiffDataKeys.CURRENT_CONTENT] for [JujutsuDataKeys.DIFF_CONTENT_INFO] user data.
 * Diff actions annotate historical (non-local) content sides with this data so the
 * action can resolve repo, file path, and commit without a backing [VirtualFile].
 */
class OpenInRemoteFromEditorGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val repo = e?.repoForFileOrDiff ?: return emptyArray()
        return RemoteUrlBuilder.classifiedRemotes(repo.gitRemotes).map { remote ->
            editorRemoteAction(remote)
        }.toTypedArray()
    }

    override fun update(e: AnActionEvent) {
        val repo = e.repoForFileOrDiff ?: run {
            e.presentation.isVisible = false
            return
        }
        applyRemoteVisibility(e, RemoteUrlBuilder.classifiedRemotes(repo.gitRemotes).size)
    }
}

/** Reads [JujutsuDataKeys.DIFF_CONTENT_INFO] from [DiffDataKeys.CURRENT_CONTENT] if present. */
private val AnActionEvent.diffContentInfo: DiffContentInfo?
    get() = getData(DiffDataKeys.CURRENT_CONTENT)?.getUserData(JujutsuDataKeys.DIFF_CONTENT_INFO)

/** Resolves the [JujutsuRepository] from the event, trying real-file context first, then diff content. */
private val AnActionEvent.repoForFileOrDiff: JujutsuRepository?
    get() = repoForFile ?: diffContentInfo?.repo

private fun editorRemoteAction(remote: ClassifiedRemote) = object : DumbAwareAction(
    JujutsuBundle.message("log.action.open.in.remote.named", remote.kind.label, remote.name),
    JujutsuBundle.message("action.open.file.in.remote.from.editor.description"),
    remote.kind.icon
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Try real-file context first (regular editor or local side of a diff)
        val localFile = e.file
        val localRepo = e.repoForFile
        if (localFile != null && localRepo != null) {
            val logEntry = project.possibleLogEntryFor(localFile)
            val pinnedCommitId = logEntry?.takeUnless { it.isWorkingCopy }?.commitId
            openInRemote(project, localRepo, remote, localRepo.getRelativePath(localFile), pinnedCommitId)
            return
        }
        // Fall back to diff content info (historical side of a diff viewer)
        val info = e.diffContentInfo ?: return
        openInRemote(project, info.repo, remote, info.repo.getRelativePath(info.filePath), info.commitId)
    }
}

private fun openInRemote(
    project: Project,
    repo: JujutsuRepository,
    remote: ClassifiedRemote,
    relativePath: String,
    pinnedCommitId: CommitId?
) {
    runInBackground {
        val commitHash = pinnedCommitId?.full
            ?: repo.commandExecutor.latestPushedAncestorCommitId(remote.name)
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
