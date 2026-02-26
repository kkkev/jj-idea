package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.invalidate

/**
 * Factory action for fetching from Git remote for a specific repository.
 * Used in log context menu where a single repo is known.
 */
fun gitFetchAction(project: Project, repo: JujutsuRepository?) =
    nullAndDumbAwareAction(repo, "log.action.git.fetch", AllIcons.Vcs.Fetch) {
        target.commandExecutor
            .createCommand { gitFetch() }
            .onSuccess {
                target.invalidate()
                log.info("Fetched for ${target.displayName}")
            }
            .onFailureTellUser("action.git.fetch.error", project, log)
            .executeWithProgress(project, JujutsuBundle.message("progress.git.fetch"))
    }

/**
 * Factory action for pushing to Git remote for a specific repository.
 * Loads remotes/bookmarks off EDT, then opens the push dialog.
 */
fun gitPushAction(project: Project, repo: JujutsuRepository?) =
    nullAndDumbAwareAction(repo, "log.action.git.push", AllIcons.Vcs.Push) {
        // Load remotes and bookmarks off EDT, then show dialog on EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            val (remotes, bookmarks) = GitPushDialog.loadDialogData(target)

            ApplicationManager.getApplication().invokeLater {
                val dialog = GitPushDialog(project, remotes, bookmarks)
                if (!dialog.showAndGet()) return@invokeLater

                val spec = dialog.result ?: return@invokeLater

                target.commandExecutor
                    .createCommand { gitPush(spec.remote, spec.bookmark, spec.allBookmarks) }
                    .onSuccess {
                        target.invalidate()
                        log.info("Pushed for ${target.displayName}")
                    }
                    .onFailureTellUser("action.git.push.error", project, log)
                    .executeWithProgress(project, JujutsuBundle.message("progress.git.push"))
            }
        }
    }
