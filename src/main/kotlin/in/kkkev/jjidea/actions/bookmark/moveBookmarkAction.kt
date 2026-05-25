package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.util.runLater

fun moveBookmarkAction(entry: LogEntry?) = nullAndDumbAwareAction(
    entry,
    "action.bookmark.move",
    AllIcons.Actions.MoveUp
) {
    val repo = target.repo
    val targetId = target.id
    MoveBookmarkDialog.show(repo, targetId) { bookmark, allowBackwards ->
        executeMove(repo, bookmark, targetId, allowBackwards)
    }
}

private fun executeMove(
    repo: JujutsuRepository,
    bookmark: Bookmark,
    targetId: ChangeId,
    allowBackwards: Boolean
) {
    repo.commandExecutor.createCommand { bookmarkSet(bookmark, targetId, allowBackwards) }
        .onSuccess { repo.invalidate() }
        .onFailure {
            if (!allowBackwards && exitCode == 1 && stderr.contains("backwards or sideways")) {
                // Race: repo state diverged since the dialog classified this as forward.
                runLater { promptRaceBackwards(repo.project, repo, bookmark, targetId) }
            } else {
                tellUser(repo.project, "action.bookmark.move.error")
            }
        }
        .executeAsync()
}

private fun promptRaceBackwards(
    project: Project,
    repo: JujutsuRepository,
    bookmark: Bookmark,
    targetId: ChangeId
) {
    if (Messages.showYesNoDialog(
            project,
            JujutsuBundle.message("action.bookmark.move.backwards.message", bookmark),
            JujutsuBundle.message("action.bookmark.move.backwards.title"),
            Messages.getWarningIcon()
        ) == Messages.YES
    ) {
        executeMove(repo, bookmark, targetId, allowBackwards = true)
    }
}
