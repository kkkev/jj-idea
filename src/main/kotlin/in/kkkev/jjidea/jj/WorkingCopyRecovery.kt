package `in`.kkkev.jjidea.jj

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Evaluates [block] with [this]'s working copy, or returns null when it is currently unavailable
 * (stale workspace, unreadable store - jj-idea-b65g) instead of letting
 * [WorkingCopyUnavailableException] escape. For passive reads only - rendering, an action's
 * `update()`, [com.intellij.openapi.actionSystem.UiDataProvider.uiDataSnapshot] - where there is
 * no user-initiated operation worth retrying once the workspace is repaired. For that case, see
 * [runRecoverable].
 */
internal inline fun <T> JujutsuRepository.whenWorkingCopyAvailable(block: (LogEntry) -> T): T? =
    try {
        block(workingCopy)
    } catch (_: WorkingCopyUnavailableException) {
        null
    }

/**
 * Runs [block]; if it fails because a repo's working copy is unavailable
 * ([WorkingCopyUnavailableException] - jj-idea-b65g), shows a notification offering that repo's
 * [RepositoryHealth] remedy (update the stale workspace, or retry/reconfigure for an unreadable
 * one) and re-runs [block] once the user completes it - so a user-initiated action (e.g. Advance
 * Bookmark) actually finishes once the workspace is repaired, instead of just failing silently or
 * crashing with an uncaught exception.
 */
fun runRecoverable(project: Project, block: () -> Unit) {
    try {
        block()
    } catch (e: WorkingCopyUnavailableException) {
        JujutsuNotifications.notifyWorkingCopyUnavailable(project, e.repo, e.health) {
            runRecoverable(project, block)
        }
    }
}

/**
 * Runs [block] on a background thread (jj-idea-27b4); classifies any thrown [VcsException] and
 * always makes the failure visible rather than swallowing it into an empty result or a log-only
 * line (contributing.md's Error Handling Philosophy). A stale-workspace failure offers the
 * "Update Stale Workspace" remedy, re-running via [retry] once applied; anything else calls
 * [onError] (default: a generic visible error notification) on the EDT.
 *
 * Covers the raw `commandExecutor.log(...)`/`logService.getBookmarks()`/`repo.logCache[...]`-style
 * reads that bypass [CommandExecutor.Command] entirely (dialog data loaders, candidate pickers),
 * which the central stale intercept in [CommandExecutor.Command.executeAsync] can't reach.
 */
fun JujutsuRepository.runRecoverableInBackground(
    retry: () -> Unit = {},
    modalityState: ModalityState = ModalityState.defaultModalityState(),
    onError: (VcsException) -> Unit = { e ->
        JujutsuNotifications.notify(
            project,
            JujutsuBundle.message("notification.background.error.title"),
            e.message.orEmpty(),
            NotificationType.ERROR
        )
    },
    block: () -> Unit
) = runInBackground(modalityState) {
    try {
        block()
    } catch (e: VcsException) {
        val health = classifyRepositoryFailure(e.message.orEmpty())
        runLater {
            if (health is RepositoryHealth.Stale) {
                JujutsuNotifications.notifyWorkingCopyUnavailable(
                    project,
                    this@runRecoverableInBackground,
                    health,
                    retry
                )
            } else {
                onError(e)
            }
        }
    }
}

/**
 * Runs `jj workspace update-stale` for [repo] (jj-idea-b65g) and, on success, invalidates its
 * state and calls [onRepaired] - the shared remedy behind both the background
 * [JujutsuNotifications.notifyUnreadableRoot] balloon and [runRecoverable]'s retry.
 */
fun updateStaleWorkspace(project: Project, repo: JujutsuRepository, onRepaired: () -> Unit = {}) {
    repo.createCommand { workspaceUpdateStale() }
        .onSuccess {
            repo.invalidate(vfsChanged = true)
            onRepaired()
        }
        .onFailure { tellUser(project, "notification.stale.error") }
        .executeAsync()
}
