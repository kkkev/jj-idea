package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.bookmarkTarget
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository

/**
 * Rename bookmark: opens [RenameBookmarkDialog]. Shared by [renameBookmarkAction] (fixed-target
 * factory) and [RenameBookmarkAction] (jj-idea-ib1i, registered/keymap-assignable).
 */
internal fun performRenameBookmark(repo: JujutsuRepository, bookmark: Bookmark) {
    RenameBookmarkDialog(repo, bookmark).show()
}

fun renameBookmarkAction(repo: JujutsuRepository, bookmark: Bookmark) = nullAndDumbAwareAction(
    bookmark,
    "action.bookmark.rename",
    AllIcons.Actions.Edit
) { performRenameBookmark(repo, target) }

/**
 * Registered, keymap-assignable form of [renameBookmarkAction] (jj-idea-ib1i, GitHub #48 split
 * 1/3).
 */
class RenameBookmarkAction : DumbAwareAction(
    JujutsuBundle.message("action.bookmark.rename.generic"),
    JujutsuBundle.message("action.bookmark.rename.tooltip.generic"),
    AllIcons.Actions.Edit
) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.bookmarkTarget?.bookmark != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val target = e.bookmarkTarget ?: return
        performRenameBookmark(target.repo, target.bookmark)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class RenameBookmarkDialog(repo: JujutsuRepository, private val oldBookmark: Bookmark) :
    BookmarkNameDialog(repo, "rename") {
    init {
        nameField.text = oldBookmark.name.name
        nameField.selectAll()
    }

    override fun execute(executor: CommandExecutor) =
        executor.bookmarkRename(oldBookmark.name, bookmark)

    override fun onSuccess() {
        log.info("Renamed bookmark ${oldBookmark.name} to ${bookmark.name}")
    }
}
