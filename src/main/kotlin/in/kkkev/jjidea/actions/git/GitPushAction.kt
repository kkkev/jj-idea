package `in`.kkkev.jjidea.actions.git

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * Push to a Git remote. Loads remotes/bookmarks off EDT, then opens a dialog to configure options.
 * Registered in plugin.xml and added to the VCS menu and log toolbar.
 *
 * When multiple repositories exist, the dialog includes a repository selector pre-populated from
 * the file context (if any). The push targets only the repo selected in the dialog.
 */
class GitPushAction : DumbAwareAction(
    JujutsuBundle.message("action.git.push"),
    JujutsuBundle.message("action.git.push.description"),
    AllIcons.Vcs.Push
) {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project.isJujutsu
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repos = project.initialisedJujutsuRepositories
        if (repos.isEmpty()) return
        val initialRepo = e.repoForFile ?: repos.first()

        // Load remotes and bookmarks for all repos off EDT, then show dialog on EDT
        runInBackground {
            val allData = GitPushDialog.loadAllDialogData(repos)

            runLater {
                val dialog = GitPushDialog(project, allData, initialRepo)
                if (!dialog.showAndGet()) return@runLater

                val spec = dialog.result ?: return@runLater

                spec.repo.commandExecutor
                    .createCommand {
                        val bm = spec.bookmark
                        gitPush(spec.remote, bm, spec.allBookmarks, allowNew = bm != null && !bm.tracked)
                    }
                    .onSuccess { stdout ->
                        spec.repo.invalidate()
                        log.info("Pushed for ${spec.repo.displayName}")
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
