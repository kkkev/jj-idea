package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.invalidate

fun forgetBookmarkAction(repo: JujutsuRepository, bookmark: Bookmark) = nullAndDumbAwareAction(
    bookmark,
    "action.bookmark.forget",
    AllIcons.General.Remove
) {
    if (Messages.showYesNoDialog(
            repo.project,
            JujutsuBundle.message("action.bookmark.forget.confirm.message", bookmark),
            JujutsuBundle.message("action.bookmark.forget.confirm.title", bookmark),
            Messages.getWarningIcon()
        ) != Messages.YES
    ) {
        log.info("User cancelled forgetting bookmark $bookmark")
        return@nullAndDumbAwareAction
    }

    repo.commandExecutor.createCommand { bookmarkForget(bookmark) }
        .onSuccess {
            repo.invalidate()
            log.info("Forgot bookmark $bookmark")
        }.onFailure { tellUser(repo.project, "action.bookmark.forget.error") }
        .executeAsync()
}
