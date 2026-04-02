package `in`.kkkev.jjidea.actions.git

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.isJujutsu
import `in`.kkkev.jjidea.vcs.jujutsuRepositories

/**
 * Push to a Git remote. Loads remotes/bookmarks off EDT, then opens a dialog to configure options.
 * Registered in plugin.xml and added to the VCS menu and log toolbar.
 */
class GitPushAction : DumbAwareAction(
    JujutsuBundle.message("action.git.push"),
    JujutsuBundle.message("action.git.push.description"),
    AllIcons.Vcs.Push
) {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.isJujutsu == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repos = project.jujutsuRepositories.filter { it.isInitialised }
        if (repos.isEmpty()) return

        val repo = repos.first()

        // Load remotes and bookmarks off EDT, then show dialog on EDT
        runInBackground {
            val data = GitPushDialog.loadDialogData(repo)

            runLater {
                val dialog = GitPushDialog(project, data)
                if (!dialog.showAndGet()) return@runLater

                val spec = dialog.result ?: return@runLater

                repos.forEach { targetRepo ->
                    targetRepo.commandExecutor
                        .createCommand {
                            val bm = spec.bookmark
                            if (bm != null && !bm.tracked) {
                                val trackResult = bookmarkTrack(Bookmark("${bm.localName}@${spec.remote}"))
                                if (!trackResult.isSuccess) return@createCommand trackResult
                            }
                            gitPush(spec.remote, bm, spec.allBookmarks)
                        }
                        .onSuccess { stdout ->
                            targetRepo.invalidate()
                            log.info("Pushed for ${targetRepo.displayName}")
                            JujutsuNotifications.notify(
                                project,
                                JujutsuBundle.message("action.git.push.success.title"),
                                stdout.ifBlank { JujutsuBundle.message("action.git.push.success.message.default") },
                                NotificationType.INFORMATION
                            )
                        }
                        .onFailure { tellUser(project, "action.git.push.error") }
                        .executeWithProgress(project, JujutsuBundle.message("progress.git.push"))
                }
            }
        }
    }
}
