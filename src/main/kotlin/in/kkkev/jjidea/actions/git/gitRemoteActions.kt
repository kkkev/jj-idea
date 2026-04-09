package `in`.kkkev.jjidea.actions.git

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Factory action for fetching from Git remote for a specific repository.
 * Used in log context menu where a single repo is known.
 */
fun gitFetchAction(project: Project, repo: JujutsuRepository?) =
    nullAndDumbAwareAction(repo, "log.action.git.fetch", AllIcons.Vcs.Fetch) {
        runInBackground {
            val remotes = GitPushDialog.loadRemotes(target)
            if (remotes.isEmpty()) {
                runLater {
                    JujutsuNotifications.notify(
                        project,
                        JujutsuBundle.message("action.git.no.remote.title"),
                        JujutsuBundle.message("action.git.no.remote.message"),
                        NotificationType.WARNING
                    )
                }
                return@runInBackground
            }
            target.commandExecutor
                .createCommand { gitFetch() }
                .onSuccess {
                    target.invalidate()
                    log.info("Fetched for ${target.displayName}")
                }
                .onFailure { tellUser(project, "action.git.fetch.error") }
                .executeWithProgress(project, JujutsuBundle.message("progress.git.fetch"))
        }
    }

/**
 * Factory action for pushing to Git remote for a specific repository.
 * Loads remotes/bookmarks off EDT, then opens the push dialog.
 * @param revision When provided, bookmarks are filtered to those on this revision or its ancestors,
 *   and the push command targets this specific revision.
 */
fun gitPushAction(project: Project, repo: JujutsuRepository?, revision: Revision? = null) =
    nullAndDumbAwareAction(repo, "log.action.git.push", AllIcons.Vcs.Push) {
        // Load remotes and bookmarks off EDT, then show dialog on EDT
        runInBackground {
            val data = GitPushDialog.loadDialogData(target, revision)
            if (data.remotes.isEmpty()) {
                runLater {
                    JujutsuNotifications.notify(
                        project,
                        JujutsuBundle.message("action.git.no.remote.title"),
                        JujutsuBundle.message("action.git.no.remote.message"),
                        NotificationType.WARNING
                    )
                }
                return@runInBackground
            }

            runLater {
                val dialog = GitPushDialog(project, mapOf(target to data), target)
                if (!dialog.showAndGet()) return@runLater

                val spec = dialog.result ?: return@runLater

                target.commandExecutor
                    .createCommand {
                        val bm = spec.bookmark
                        gitPush(
                            spec.remote,
                            bm,
                            spec.allBookmarks,
                            allowNew = bm != null && !bm.tracked,
                            revision = revision
                        )
                    }
                    .onSuccess { stdout ->
                        target.invalidate()
                        log.info("Pushed for ${target.displayName}")
                        JujutsuNotifications.notify(
                            project,
                            JujutsuBundle.message("action.git.push.success.title"),
                            stdout.ifBlank { JujutsuBundle.message("action.git.push.success.message.default") },
                            NotificationType.INFORMATION
                        )
                    }
                    .onFailure {
                        tellUser(project, "action.git.push.error")
                    }
                    .executeWithProgress(project, JujutsuBundle.message("progress.git.push"))
            }
        }
    }
