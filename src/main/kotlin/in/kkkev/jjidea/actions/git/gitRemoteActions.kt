package `in`.kkkev.jjidea.actions.git

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

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
            .onFailure { tellUser(project, "action.git.fetch.error") }
            .executeWithProgress(project, JujutsuBundle.message("progress.git.fetch"))
    }

/**
 * Factory action for pushing to Git remote for a specific repository.
 * Loads remotes/bookmarks off EDT, then opens the push dialog.
 */
fun gitPushAction(project: Project, repo: JujutsuRepository?) =
    nullAndDumbAwareAction(repo, "log.action.git.push", AllIcons.Vcs.Push) {
        // Load remotes and bookmarks off EDT, then show dialog on EDT
        runInBackground {
            val data = GitPushDialog.loadDialogData(target)

            runLater {
                val dialog = GitPushDialog(project, data)
                if (!dialog.showAndGet()) return@runLater

                val spec = dialog.result ?: return@runLater

                target.commandExecutor
                    .createCommand { gitPush(spec.remote, spec.bookmark, spec.allBookmarks) }
                    .onSuccess {
                        target.invalidate()
                        log.info("Pushed for ${target.displayName}")
                    }
                    .onFailure {
                        tellUser(project, "action.git.push.error")
                    }
                    .executeWithProgress(project, JujutsuBundle.message("progress.git.push"))
            }
        }
    }
