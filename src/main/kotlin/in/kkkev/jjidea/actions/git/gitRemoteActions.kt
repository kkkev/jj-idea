package `in`.kkkev.jjidea.actions.git

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

private val log = Logger.getInstance("in.kkkev.jjidea.actions.git.gitRemoteActions")

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

                checkAndPush(spec, project, revision)
            }
        }
    }

/**
 * Runs a dry-run push to detect non-fast-forward bookmark moves, then shows a confirmation
 * dialog if needed before performing the actual push. Called on EDT.
 *
 * - Tracked bookmarks moved backwards or sideways: jj reports direction in dry-run stderr → warn.
 * - Untracked bookmarks: jj has no old position, refuses dry-run → warn that direction is unknown.
 * - Forward or new bookmarks: proceed silently.
 */
fun checkAndPush(spec: GitPushDialog.GitPushSpec, project: Project, revision: Revision? = null) {
    runInBackground {
        val bm = spec.bookmark
        val dryRun = spec.repo.commandExecutor.gitPush(
            remote = spec.remote,
            bookmark = bm,
            allBookmarks = spec.allBookmarks,
            allowNew = false,
            revision = revision,
            dryRun = true
        )

        if (!dryRun.isSuccess) {
            if (dryRun.stderr.contains("Refusing to create new remote bookmark")) {
                runLater {
                    if (confirmUntrackedPush(project)) performPush(spec, project, revision)
                }
            } else {
                runLater { dryRun.tellUser(project, "action.git.push.error") }
            }
            return@runInBackground
        }

        val forcePushBookmarks = parseForcePushBookmarks(dryRun.stderr)

        runLater {
            if (forcePushBookmarks.isEmpty() || confirmForcePush(project, forcePushBookmarks)) {
                performPush(spec, project, revision)
            }
        }
    }
}

private fun performPush(spec: GitPushDialog.GitPushSpec, project: Project, revision: Revision?) {
    spec.repo.commandExecutor
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

// jj outputs "Move sideways bookmark X from Y to Z" or "Move backward bookmark X from Y to Z"
// for non-fast-forward pushes. Both require a force-push and must be confirmed.
private val FORCE_PUSH_MARKERS = listOf("Move sideways bookmark ", "Move backward bookmark ")

/** Parses dry-run stderr for non-fast-forward bookmark moves, returning the bookmark names. */
internal fun parseForcePushBookmarks(stderr: String): List<String> =
    stderr.lines()
        .mapNotNull { line -> FORCE_PUSH_MARKERS.firstOrNull { line.contains(it) }?.let { line to it } }
        .map { (line, marker) -> line.substringAfter(marker).substringBefore(" from ") }
        .filter { it.isNotEmpty() }

private fun confirmUntrackedPush(project: Project) =
    Messages.showYesNoDialog(
        project,
        JujutsuBundle.message("action.git.push.untracked.message"),
        JujutsuBundle.message("action.git.push.sideways.title"),
        JujutsuBundle.message("action.git.push.sideways.push"),
        JujutsuBundle.message("action.git.push.sideways.cancel"),
        Messages.getWarningIcon()
    ) == Messages.YES

private fun confirmForcePush(project: Project, bookmarks: List<String>): Boolean {
    val bookmarkList = bookmarks.joinToString("\n") { "  • $it" }
    return Messages.showYesNoDialog(
        project,
        JujutsuBundle.message("action.git.push.sideways.message", bookmarkList),
        JujutsuBundle.message("action.git.push.sideways.title"),
        JujutsuBundle.message("action.git.push.sideways.push"),
        JujutsuBundle.message("action.git.push.sideways.cancel"),
        Messages.getWarningIcon()
    ) == Messages.YES
}
