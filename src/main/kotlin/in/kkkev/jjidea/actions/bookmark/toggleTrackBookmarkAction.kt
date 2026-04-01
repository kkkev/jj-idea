package `in`.kkkev.jjidea.actions.bookmark

import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

fun toggleTrackBookmarkAction(repo: JujutsuRepository, bookmark: Bookmark) =
    if (bookmark.tracked) {
        nullAndDumbAwareAction(bookmark, "action.bookmark.untrack", JujutsuIcons.Bookmark) {
            repo.commandExecutor.createCommand { bookmarkUntrack(target) }
                .onSuccess { repo.invalidate() }
                .onFailure { tellUser(repo.project, "action.bookmark.untrack.error") }
                .executeAsync()
        }
    } else {
        nullAndDumbAwareAction(bookmark, "action.bookmark.track", JujutsuIcons.BookmarkTracked) {
            repo.commandExecutor.createCommand { bookmarkTrack(target) }
                .onSuccess { repo.invalidate() }
                .onFailure { tellUser(repo.project, "action.bookmark.track.error") }
                .executeAsync()
        }
    }
