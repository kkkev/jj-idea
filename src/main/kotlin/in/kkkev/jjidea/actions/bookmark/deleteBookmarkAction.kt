package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.bookmarkTarget
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

private val log = Logger.getInstance("in.kkkev.jjidea.actions.bookmark.deleteBookmarkAction")

/**
 * Confirms, then runs `jj bookmark delete`. Shared implementation behind [deleteBookmarkAction]
 * (fixed-target factory) and [DeleteBookmarkAction] (jj-idea-ib1i, registered/keymap-assignable) -
 * one implementation, two entry points, mirroring
 * [in.kkkev.jjidea.actions.change.rebaseAction]/`performRebase`.
 */
internal fun performDeleteBookmark(repo: JujutsuRepository, bookmark: Bookmark) {
    if (Messages.showYesNoDialog(
            repo.project,
            JujutsuBundle.message("action.bookmark.delete.confirm.message", bookmark.name),
            JujutsuBundle.message("action.bookmark.delete.confirm.title", bookmark.name),
            Messages.getWarningIcon()
        ) != Messages.YES
    ) {
        log.info("User cancelled deletion of bookmark ${bookmark.name}")
        return
    }

    repo.commandExecutor.createCommand { bookmarkDelete(bookmark.name) }
        .onSuccess {
            repo.invalidate()
            log.info("Deleted bookmark ${bookmark.name}")
        }.onFailure { tellUser(repo.project, "action.bookmark.delete.error") }
        .executeAsync()
}

fun deleteBookmarkAction(repo: JujutsuRepository, bookmark: Bookmark) = nullAndDumbAwareAction(
    bookmark,
    "action.bookmark.delete",
    JujutsuIcons.BookmarkDelete
) { performDeleteBookmark(repo, target) }

/**
 * Registered, keymap-assignable form of [deleteBookmarkAction] (jj-idea-ib1i, GitHub #48 split
 * 1/3): reads its target from the bookmarks panel's selection
 * ([in.kkkev.jjidea.actions.JujutsuDataKeys.BOOKMARK_TARGET]) instead of a fixed closure.
 */
class DeleteBookmarkAction : DumbAwareAction(
    JujutsuBundle.message("action.bookmark.delete.generic"),
    JujutsuBundle.message("action.bookmark.delete.tooltip.generic"),
    JujutsuIcons.BookmarkDelete
) {
    override fun update(e: AnActionEvent) {
        val bookmark = e.bookmarkTarget?.bookmark
        e.presentation.isEnabled = bookmark != null
        e.presentation.text = bookmark?.let { JujutsuBundle.message("action.bookmark.delete", it.name) }
            ?: JujutsuBundle.message("action.bookmark.delete.generic")
        e.presentation.description = bookmark?.let { JujutsuBundle.message("action.bookmark.delete.tooltip", it.name) }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val target = e.bookmarkTarget ?: return
        performDeleteBookmark(target.repo, target.bookmark)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
