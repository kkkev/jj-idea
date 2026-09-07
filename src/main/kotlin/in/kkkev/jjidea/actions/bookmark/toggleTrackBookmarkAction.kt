package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.bookmarkTarget
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

/** Runs `jj bookmark untrack`/`track`. Shared by [toggleTrackBookmarkAction] and [ToggleTrackBookmarkAction]. */
internal fun performToggleTrack(repo: JujutsuRepository, bookmark: Bookmark) {
    if (bookmark.tracked) {
        repo.commandExecutor.createCommand { bookmarkUntrack(bookmark.name) }
            .onSuccess { repo.invalidate() }
            .onFailure { tellUser(repo.project, "action.bookmark.untrack.error") }
            .executeAsync()
    } else {
        repo.commandExecutor.createCommand { bookmarkTrack(listOf(bookmark.name)) }
            .onSuccess { repo.invalidate() }
            .onFailure { tellUser(repo.project, "action.bookmark.track.error") }
            .executeAsync()
    }
}

fun toggleTrackBookmarkAction(repo: JujutsuRepository, bookmark: Bookmark) =
    if (bookmark.tracked) {
        nullAndDumbAwareAction(bookmark, "action.bookmark.untrack", JujutsuIcons.BookmarkAction) {
            performToggleTrack(repo, target)
        }
    } else {
        nullAndDumbAwareAction(bookmark, "action.bookmark.track", JujutsuIcons.BookmarkTrackedAction) {
            performToggleTrack(repo, target)
        }
    }

/**
 * Registered, keymap-assignable form of [toggleTrackBookmarkAction] (jj-idea-ib1i, GitHub #48
 * split 1/3): a single action id whose label/icon flip between Track/Untrack in [update] based on
 * the current bookmark's tracked state, rather than two separate registered actions.
 */
class ToggleTrackBookmarkAction : DumbAwareAction(
    JujutsuBundle.message("action.bookmark.toggletrack.generic"),
    JujutsuBundle.message("action.bookmark.toggletrack.tooltip.generic"),
    JujutsuIcons.BookmarkAction
) {
    override fun update(e: AnActionEvent) {
        val bookmark = e.bookmarkTarget?.bookmark
        e.presentation.isEnabled = bookmark != null
        val untracked = bookmark?.tracked == false
        e.presentation.icon = if (untracked) JujutsuIcons.BookmarkTrackedAction else JujutsuIcons.BookmarkAction
        e.presentation.text = when {
            bookmark == null -> JujutsuBundle.message("action.bookmark.toggletrack.generic")
            bookmark.tracked -> JujutsuBundle.message("action.bookmark.untrack", bookmark.name)
            else -> JujutsuBundle.message("action.bookmark.track", bookmark.name)
        }
        e.presentation.description = when {
            bookmark == null -> null
            bookmark.tracked -> JujutsuBundle.message("action.bookmark.untrack.tooltip", bookmark.name)
            else -> JujutsuBundle.message("action.bookmark.track.tooltip", bookmark.name)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val target = e.bookmarkTarget ?: return
        performToggleTrack(target.repo, target.bookmark)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
