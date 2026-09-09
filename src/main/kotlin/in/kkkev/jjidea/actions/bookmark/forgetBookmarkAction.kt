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
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

private val log = Logger.getInstance("in.kkkev.jjidea.actions.bookmark.forgetBookmarkAction")

/**
 * Confirms, then runs `jj bookmark forget`. Shared by [forgetBookmarkAction] (fixed-target
 * factory) and [ForgetBookmarkAction] (jj-idea-ib1i, registered/keymap-assignable).
 */
internal fun performForgetBookmark(repo: JujutsuRepository, bookmark: Bookmark) {
    if (Messages.showYesNoDialog(
            repo.project,
            JujutsuBundle.message("action.bookmark.forget.confirm.message", bookmark.name),
            JujutsuBundle.message("action.bookmark.forget.confirm.title", bookmark.name),
            Messages.getWarningIcon()
        ) != Messages.YES
    ) {
        log.info("User cancelled forgetting bookmark ${bookmark.name}")
        return
    }

    repo.createCommand { bookmarkForget(bookmark.name) }
        .onSuccess {
            repo.invalidate()
            log.info("Forgot bookmark ${bookmark.name}")
        }.onFailure { tellUser(repo.project, "action.bookmark.forget.error") }
        .executeAsync()
}

fun forgetBookmarkAction(repo: JujutsuRepository, bookmark: Bookmark) = nullAndDumbAwareAction(
    bookmark,
    "action.bookmark.forget",
    JujutsuIcons.BookmarkForget
) { performForgetBookmark(repo, target) }

/**
 * Registered, keymap-assignable form of [forgetBookmarkAction] (jj-idea-ib1i, GitHub #48 split
 * 1/3).
 */
class ForgetBookmarkAction : DumbAwareAction(
    JujutsuBundle.message("action.bookmark.forget.generic"),
    JujutsuBundle.message("action.bookmark.forget.tooltip.generic"),
    JujutsuIcons.BookmarkForget
) {
    override fun update(e: AnActionEvent) {
        val bookmark = e.bookmarkTarget?.bookmark
        e.presentation.isEnabled = bookmark != null
        e.presentation.text = bookmark?.let { JujutsuBundle.message("action.bookmark.forget", it.name) }
            ?: JujutsuBundle.message("action.bookmark.forget.generic")
        e.presentation.description = bookmark?.let { JujutsuBundle.message("action.bookmark.forget.tooltip", it.name) }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val target = e.bookmarkTarget ?: return
        performForgetBookmark(target.repo, target.bookmark)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
