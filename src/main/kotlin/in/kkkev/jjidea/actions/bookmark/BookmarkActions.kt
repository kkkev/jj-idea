package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.openapi.actionSystem.AnAction
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository

/**
 * Actions for a local bookmark, shared by every surface that lists them: the bookmark widget's
 * per-bookmark submenu ([in.kkkev.jjidea.ui.log.JujutsuBookmarkWidget]), the log context menu's
 * bookmark submenu, and a right-click on a bookmark chip
 * ([in.kkkev.jjidea.ui.log.JujutsuLogContextMenuActions]). Kept in one place so a future bookmark
 * management surface (jj-idea-b2ae) — or a new per-bookmark action — only needs to be added here
 * once. See jj-idea-reiz.
 *
 * @param includeMoveToChange Whether to offer "Move to Change..." — omitted where the bookmark is
 *   already known to sit on the revision being shown (moving it there would be a no-op).
 */
fun localBookmarkActions(repo: JujutsuRepository, bookmark: Bookmark, includeMoveToChange: Boolean): List<AnAction> =
    buildList {
        if (includeMoveToChange) add(moveBookmarkToChangeAction(repo, bookmark))
        add(advanceBookmarkAction(repo, bookmark))
        add(renameBookmarkAction(repo, bookmark))
        add(deleteBookmarkAction(repo, bookmark))
        add(forgetBookmarkAction(repo, bookmark))
    }

/** Actions for a remote bookmark, shared the same way as [localBookmarkActions]. */
fun remoteBookmarkActions(repo: JujutsuRepository, bookmark: Bookmark): List<AnAction> =
    listOf(toggleTrackBookmarkAction(repo, bookmark))
