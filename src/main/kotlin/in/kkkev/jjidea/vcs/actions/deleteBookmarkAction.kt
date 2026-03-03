package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.invalidate

/**
 * Delete bookmark action.
 * Deletes the bookmark with confirmation.
 */
fun deleteBookmarkAction(repo: JujutsuRepository, bookmark: Bookmark) = nullAndDumbAwareAction(
    bookmark,
    "action.bookmark.delete",
    AllIcons.General.Delete
) {
    if (Messages.showYesNoDialog(
            repo.project,
            JujutsuBundle.message("action.bookmark.delete.confirm.message", bookmark),
            JujutsuBundle.message("action.bookmark.delete.confirm.title", bookmark),
            Messages.getWarningIcon()
        ) != Messages.YES
    ) {
        log.info("User cancelled deletion of bookmark $bookmark")
        return@nullAndDumbAwareAction
    }

    repo.commandExecutor.createCommand { bookmarkDelete(bookmark) }
        .onSuccess {
            repo.invalidate()
            log.info("Deleted bookmark $bookmark")
        }.onFailure { tellUser(repo.project, "action.bookmark.delete.error") }
        .executeAsync()
}
