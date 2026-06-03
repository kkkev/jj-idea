package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
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
            JujutsuBundle.message("action.bookmark.delete.confirm.message", bookmark.name),
            JujutsuBundle.message("action.bookmark.delete.confirm.title", bookmark.name),
            Messages.getWarningIcon()
        ) != Messages.YES
    ) {
        log.info("User cancelled deletion of bookmark ${bookmark.name}")
        return@nullAndDumbAwareAction
    }

    repo.commandExecutor.createCommand { bookmarkDelete(bookmark.name) }
        .onSuccess {
            repo.invalidate()
            log.info("Deleted bookmark ${bookmark.name}")
        }.onFailure { tellUser(repo.project, "action.bookmark.delete.error") }
        .executeAsync()
}
