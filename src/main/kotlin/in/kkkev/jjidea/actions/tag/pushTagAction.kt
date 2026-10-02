package `in`.kkkev.jjidea.actions.tag

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.git.applyRemoteVisibility
import `in`.kkkev.jjidea.actions.git.pushSuccessMessage
import `in`.kkkev.jjidea.jj.JjFeature
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Remote
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.disabledReasonIn
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.isSupportedIn
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications

private val log = Logger.getInstance("in.kkkev.jjidea.actions.tag.pushTagAction")

/**
 * Pushes [tag] to a remote via `jj git push --tag` (GitHub #124, jj-idea-k9oy). Follows the 0/1/2+
 * remote convention of [in.kkkev.jjidea.actions.bookmark.pushBookmarkAction]: a single inline
 * action with one remote, a "Push 'X' to ▸" submenu with several, hidden with none.
 *
 * Gated on [JjFeature.TAG_PUSH] (jj 0.44+): visible but disabled with the reason on older jj.
 * There is no dry-run/untracked-tag handling as for bookmarks — jj tracks a tag automatically on
 * first push — so a confirmation prompt is the only review step before mutating the remote.
 */
fun pushTagAction(repo: JujutsuRepository, tag: Tag): DefaultActionGroup =
    object : DefaultActionGroup() {
        init {
            templatePresentation.icon = AllIcons.Vcs.Push
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun getChildren(e: AnActionEvent?): Array<AnAction> =
            repo.cachedGitRemotes.map { pushTagToRemoteAction(repo, tag, Remote(it.name)) }.toTypedArray()

        override fun update(e: AnActionEvent) {
            applyRemoteVisibility(
                e,
                repo.cachedGitRemotes.size,
                JujutsuBundle.message("action.tag.push.popup", tag.name)
            )
        }
    }

private fun pushTagToRemoteAction(repo: JujutsuRepository, tag: Tag, remote: Remote): AnAction =
    object : DumbAwareAction(
        JujutsuBundle.message("action.tag.push.to", tag.name, remote.name),
        JujutsuBundle.message("action.tag.push.to.tooltip", tag.name, remote.name),
        AllIcons.Vcs.Push
    ) {
        override fun update(e: AnActionEvent) {
            val supported = JjFeature.TAG_PUSH.isSupportedIn(repo.project)
            e.presentation.isEnabled = supported
            // A disabled item's tooltip can go unseen in a menu, so the reason is also appended
            // to the visible text - same pattern as advanceBookmarkAction.
            e.presentation.text = if (supported) {
                JujutsuBundle.message("action.tag.push.to", tag.name, remote.name)
            } else {
                JujutsuBundle.message("action.tag.push.disabled.version", tag.name, JjFeature.TAG_PUSH.minVersion)
            }
            e.presentation.description = JjFeature.TAG_PUSH.disabledReasonIn(repo.project)
                ?: JujutsuBundle.message("action.tag.push.to.tooltip", tag.name, remote.name)
        }

        override fun actionPerformed(e: AnActionEvent) = performPushTag(repo, tag, remote)

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

internal fun performPushTag(repo: JujutsuRepository, tag: Tag, remote: Remote) {
    val project = repo.project
    if (Messages.showYesNoDialog(
            project,
            JujutsuBundle.message("action.tag.push.confirm.message", tag.name, remote.name),
            JujutsuBundle.message("action.tag.push.confirm.title", tag.name),
            Messages.getQuestionIcon()
        ) != Messages.YES
    ) {
        log.info("User cancelled push of tag ${tag.name} to ${remote.name}")
        return
    }

    repo.createCommand { gitPush(remote = remote, tag = tag) }
        .onSuccessResult {
            repo.invalidate()
            log.info("Pushed tag ${tag.name} to ${remote.name}")
            JujutsuNotifications.notify(
                project,
                JujutsuBundle.message("action.git.push.success.title"),
                pushSuccessMessage(result.stdout, result.stderr),
                NotificationType.INFORMATION
            )
        }
        .onFailure { tellUser("action.tag.push.error") }
        .executeWithProgress(project, JujutsuBundle.message("progress.git.push"))
}
