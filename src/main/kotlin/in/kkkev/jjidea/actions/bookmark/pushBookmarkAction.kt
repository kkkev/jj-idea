package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.git.GitPushDialog
import `in`.kkkev.jjidea.actions.git.applyRemoteVisibility
import `in`.kkkev.jjidea.actions.git.checkAndPush
import `in`.kkkev.jjidea.actions.git.noRemoteNotification
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Remote
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

internal enum class PushAvailability(val enabled: Boolean) {
    UP_TO_DATE(enabled = false),
    ENABLED(enabled = true)
}

/**
 * Whether pushing [bookmark] to one specific remote would do anything, based on that remote's own
 * remote-tracking entry ([remoteBookmark] — the `name@remote` sibling from
 * [in.kkkev.jjidea.jj.remoteEntriesFor], or null if this bookmark has never been seen on that
 * remote).
 *
 * Deliberately does **not** use [bookmark]'s own [Bookmark.aheadCount]: in a colocated repo, jj
 * auto-tracks a `@git` remote pointing at the same commit as local, so a local bookmark's
 * aggregate ahead/behind count is always `0` relative to *that* — making every bookmark look
 * permanently up to date regardless of its real Git remotes. Each `name@origin`-style entry's own
 * `tracking_ahead_count()` is specific to that one remote and has no such contamination.
 */
internal fun pushAvailability(bookmark: Bookmark, remoteBookmark: Bookmark?): PushAvailability = when {
    // Still present on this remote (remoteBookmark != null) - push removes it, regardless of
    // ahead/behind counts (a deletion isn't reflected as "ahead"). Already gone or never pushed
    // there - nothing to do.
    bookmark.deleted -> if (remoteBookmark != null) PushAvailability.ENABLED else PushAvailability.UP_TO_DATE
    remoteBookmark == null -> PushAvailability.ENABLED // never pushed to this remote - first push creates it
    remoteBookmark.aheadCount > 0 -> PushAvailability.ENABLED
    else -> PushAvailability.UP_TO_DATE
}

/**
 * Pushes a specific bookmark to a specific remote, skipping the repo/remote/bookmark selection
 * steps a fresh [GitPushDialog] would otherwise need (GitHub #81, jj-idea-t29z). Since pushing
 * mutates a shared remote, this reuses [GitPushDialog] itself rather than firing the push with no
 * review step at all: each remote's entry opens the dialog pre-selected to this bookmark's
 * "Specific bookmark" scope and that remote, so confirming is a single OK click while the
 * dialog's existing "(deleted)"/force-push/untracked-bookmark warnings stay in view first.
 *
 * Follows the 0/1/2+ remote convention used throughout `actions.git`
 * ([in.kkkev.jjidea.actions.filechange.OpenFileInRemoteGroup],
 * [in.kkkev.jjidea.actions.git.openInRemoteGroup]) — a single inline action with one remote, a
 * "Push 'X' to ▸" submenu with several — because whether there's anything to push is evaluated
 * **per remote** ([pushAvailability]): a bookmark can be up to date on `origin` but still ahead
 * on `github`. [remoteBookmarks] should be this bookmark's remote-tracking siblings, e.g. via
 * [in.kkkev.jjidea.jj.remoteEntriesFor] or [in.kkkev.jjidea.jj.BookmarkGroup.remotes].
 *
 * Also the most direct way to push a pending-deletion bookmark from the log (jj-idea-ehki): the
 * dialog's dropdown only sees a deletion when opened unscoped, but this entry point always knows
 * its bookmark directly and loads dialog data unscoped itself.
 *
 * With more than one remote, the submenu also gets a leading "push to all remotes" entry
 * (jj-idea-ndzp) ahead of the per-remote ones — a one-click convenience for the common case of
 * pushing everywhere, mirroring jjx. It still goes through [checkAndPush] once per remote that
 * has something to push, so every existing force-push/deletion/untracked-bookmark confirmation
 * still fires; it only skips [GitPushDialog] itself.
 */
fun pushBookmarkAction(
    repo: JujutsuRepository,
    bookmark: Bookmark,
    remoteBookmarks: List<Bookmark>
): DefaultActionGroup =
    object : DefaultActionGroup() {
        init {
            templatePresentation.icon = AllIcons.Vcs.Push
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val perRemote = repo.cachedGitRemotes.map { gitRemote ->
                val remoteBookmark = remoteBookmarks.find { it.remote == gitRemote.name }
                Remote(gitRemote.name) to remoteBookmark
            }
            val remoteActions = perRemote.map { (remote, remoteBookmark) ->
                pushToRemoteAction(repo, bookmark, remote, remoteBookmark)
            }
            return if (perRemote.size > 1) {
                arrayOf(
                    pushAllRemotesAction(repo, bookmark, perRemote),
                    Separator.create(),
                    *remoteActions.toTypedArray()
                )
            } else {
                remoteActions.toTypedArray()
            }
        }

        override fun update(e: AnActionEvent) {
            val popupText = JujutsuBundle.message("action.bookmark.push.popup", bookmark.name)
            applyRemoteVisibility(e, repo.cachedGitRemotes.size, popupText)
        }
    }

/**
 * One-click "push to all remotes" entry (jj-idea-ndzp): pushes [bookmark] to every remote in
 * [perRemote] where [pushAvailability] says there's something to do, each via [checkAndPush] so
 * the usual dry-run confirmations still apply. Remotes already up to date are silently skipped —
 * a bookmark can be ahead on `github` but not `origin`, and this must not produce a redundant,
 * always-a-no-op push to `origin`.
 */
private fun pushAllRemotesAction(
    repo: JujutsuRepository,
    bookmark: Bookmark,
    perRemote: List<Pair<Remote, Bookmark?>>
): DumbAwareAction = object : DumbAwareAction(
    JujutsuBundle.message("action.bookmark.push.all", bookmark.name),
    JujutsuBundle.message("action.bookmark.push.all.tooltip", bookmark.name),
    AllIcons.Vcs.Push
) {
    override fun update(e: AnActionEvent) {
        val anyEnabled = perRemote.any { (_, remoteBookmark) -> pushAvailability(bookmark, remoteBookmark).enabled }
        e.presentation.isEnabled = anyEnabled
        e.presentation.text = if (anyEnabled) {
            JujutsuBundle.message("action.bookmark.push.all", bookmark.name)
        } else {
            JujutsuBundle.message("action.bookmark.push.all.disabled.upToDate", bookmark.name)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        runInBackground {
            val data = GitPushDialog.loadDialogData(repo)
            if (data.remotes.isEmpty()) {
                runLater { noRemoteNotification(repo.project) }
                return@runInBackground
            }
            val targets = perRemote.filter { (_, remoteBookmark) -> pushAvailability(bookmark, remoteBookmark).enabled }
            runLater {
                targets.forEach { (remote, _) ->
                    checkAndPush(
                        GitPushDialog.GitPushSpec(repo, remote, bookmark, allBookmarks = false),
                        repo.project
                    )
                }
            }
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

private fun pushToRemoteAction(
    repo: JujutsuRepository,
    bookmark: Bookmark,
    remote: Remote,
    remoteBookmark: Bookmark?
): DumbAwareAction = object : DumbAwareAction(
    JujutsuBundle.message("action.bookmark.push.to", bookmark.name, remote.name),
    JujutsuBundle.message("action.bookmark.push.to.tooltip", bookmark.name, remote.name),
    AllIcons.Vcs.Push
) {
    override fun update(e: AnActionEvent) {
        val availability = pushAvailability(bookmark, remoteBookmark)
        e.presentation.isEnabled = availability.enabled
        // A disabled item's tooltip can go unseen in a menu, so the reason is also appended to
        // the visible text - same pattern as advanceBookmarkAction.
        e.presentation.text = when (availability) {
            PushAvailability.UP_TO_DATE ->
                JujutsuBundle.message("action.bookmark.push.to.disabled.upToDate", bookmark.name, remote.name)

            PushAvailability.ENABLED -> JujutsuBundle.message("action.bookmark.push.to", bookmark.name, remote.name)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        runInBackground {
            val data = GitPushDialog.loadDialogData(repo)
            if (data.remotes.isEmpty()) {
                runLater { noRemoteNotification(repo.project) }
                return@runInBackground
            }
            runLater {
                val dialog = GitPushDialog(
                    repo.project,
                    mapOf(repo to data),
                    repo,
                    initialBookmark = bookmark,
                    initialRemote = remote
                )
                if (!dialog.showAndGet()) return@runLater
                checkAndPush(dialog.result ?: return@runLater, repo.project)
            }
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
