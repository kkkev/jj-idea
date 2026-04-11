package `in`.kkkev.jjidea.actions.git

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.VcsDataKeys
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.history.JujutsuFileRevision

/**
 * Creates an action group that opens the given commit on GitHub or GitLab.
 *
 * - 0 recognized remotes or [isPushed] is false → group is hidden
 * - 1 recognized remote → group is transparent (child shown inline as "Open in GitHub/GitLab 'origin'")
 * - 2+ recognized remotes → shown as "Open on Remote" submenu
 *
 * Supports github.com and gitlab.com only. Self-hosted instances are not supported.
 * [isPushed] should be [LogEntry.immutable] as a heuristic — false means hide, true means show.
 * Remote lookup is deferred to [update]/[getChildren] (BGT) to avoid blocking the EDT.
 */
fun openInRemoteGroup(repo: JujutsuRepository, commitId: CommitId, isPushed: Boolean): DefaultActionGroup =
    object : DefaultActionGroup() {
        private val remotes by lazy { RemoteUrlBuilder.recognizedRemotes(repo.gitRemotes, commitId.full, isPushed) }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun getChildren(e: AnActionEvent?): Array<AnAction> =
            if (isPushed) remotes.map { commitAction(it) }.toTypedArray() else emptyArray()

        override fun update(e: AnActionEvent) {
            if (!isPushed) {
                e.presentation.isVisible = false
                return
            }
            applyRemoteVisibility(e, remotes.size)
        }
    }

/**
 * An action group for use in the file history toolbar. Reads the selected revision from the
 * event data context ([VcsDataKeys.VCS_FILE_REVISION]) and offers both "Open file" and
 * "Open commit" actions per recognized remote.
 *
 * - 0 recognized remotes → hidden
 * - 1 recognized remote → children shown inline (file action + commit action)
 * - 2+ recognized remotes → "Open on Remote" submenu with one sub-popup per remote
 *
 * Uses [JujutsuFileRevision.immutable] as the heuristic for whether the commit is pushed.
 * Remote lookup runs on BGT via [getActionUpdateThread].
 */
class OpenInRemoteFromHistoryGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun remotes(revision: JujutsuFileRevision) =
        RemoteUrlBuilder.recognizedRemotes(revision.repo.gitRemotes, revision.commitId.full, revision.immutable)

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val revision = e?.getData(VcsDataKeys.VCS_FILE_REVISION) as? JujutsuFileRevision
            ?: return emptyArray()
        val remotes = remotes(revision)
        return when (remotes.size) {
            0 -> emptyArray()
            1 -> {
                val remote = remotes.single()
                arrayOf(fileAction(remote, revision), commitAction(remote))
            }
            else -> remotes.map { remote ->
                DefaultActionGroup(JujutsuBundle.message("log.action.open.in.remote", remote.kind.label), true).also {
                    it.add(fileAction(remote, revision))
                    it.add(commitAction(remote))
                }
            }.toTypedArray()
        }
    }

    override fun update(e: AnActionEvent) {
        val revision = e.getData(VcsDataKeys.VCS_FILE_REVISION) as? JujutsuFileRevision ?: run {
            e.presentation.isVisible = false
            return
        }
        applyRemoteVisibility(e, remotes(revision).size)
    }
}

private fun commitAction(remote: RecognizedRemote) = object : DumbAwareAction(
    JujutsuBundle.message("log.action.open.commit.in.remote.named", remote.kind.label, remote.name),
    if (remote.isPushed) {
        JujutsuBundle.message("log.action.open.in.remote.tooltip", remote.kind.label)
    } else {
        JujutsuBundle.message("log.action.open.in.remote.not.pushed.tooltip", remote.kind.label)
    },
    remote.kind.icon
) {
    override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(remote.commitUrl)
}

private fun fileAction(remote: RecognizedRemote, revision: JujutsuFileRevision) = object : DumbAwareAction(
    JujutsuBundle.message("log.action.open.file.in.remote", remote.kind.label),
    if (remote.isPushed) {
        JujutsuBundle.message("log.action.open.file.in.remote.tooltip", remote.kind.label)
    } else {
        JujutsuBundle.message("log.action.open.file.in.remote.not.pushed.tooltip", remote.kind.label)
    },
    remote.kind.icon
) {
    override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(
        RemoteUrlBuilder.fileUrl(remote.base, remote.kind, revision.commitId.full, revision.repoRelativePath)
    )
}
