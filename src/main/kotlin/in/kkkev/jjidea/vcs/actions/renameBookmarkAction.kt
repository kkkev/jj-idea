package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository

/**
 * Delete bookmark action.
 * Deletes the bookmark with confirmation.
 */
fun renameBookmarkAction(repo: JujutsuRepository, bookmark: Bookmark) = nullAndDumbAwareAction(
    bookmark,
    "action.bookmark.rename",
    AllIcons.Actions.Edit
) {
    RenameBookmarkDialog(repo, bookmark).show()
}

class RenameBookmarkDialog(repo: JujutsuRepository, private val oldBookmark: Bookmark) :
    BookmarkNameDialog(repo, "rename") {
    override fun execute(executor: CommandExecutor) =
        executor.bookmarkRename(oldBookmark, bookmark)

    override fun onSuccess() {
        log.info("Renamed bookmark $oldBookmark to $bookmark")
    }
}
