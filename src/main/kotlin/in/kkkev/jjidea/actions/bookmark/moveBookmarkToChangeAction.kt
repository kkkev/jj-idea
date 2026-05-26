package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.util.runLater

fun moveBookmarkToChangeAction(repo: JujutsuRepository, bookmark: Bookmark) =
    nullAndDumbAwareAction(
        bookmark.takeUnless { it.deleted || it.isRemote },
        "action.bookmark.moveTo",
        AllIcons.Actions.MoveUp
    ) {
        MoveBookmarkToChangeDialog.show(repo, target) { changeId, allowBackwards ->
            executeMoveToChange(repo, target, changeId, allowBackwards)
        }
    }

private fun executeMoveToChange(
    repo: JujutsuRepository,
    bookmark: Bookmark,
    changeId: ChangeId,
    allowBackwards: Boolean
) {
    repo.commandExecutor.createCommand { bookmarkSet(bookmark, changeId, allowBackwards) }
        .onSuccess { repo.invalidate() }
        .onFailure {
            if (!allowBackwards && exitCode == 1 && stderr.contains("backwards or sideways")) {
                runLater { promptRaceBackwards(repo.project, repo, bookmark, changeId) }
            } else {
                tellUser(repo.project, "action.bookmark.moveTo.error")
            }
        }
        .executeAsync()
}

private fun promptRaceBackwards(
    project: Project,
    repo: JujutsuRepository,
    bookmark: Bookmark,
    changeId: ChangeId
) {
    if (Messages.showYesNoDialog(
            project,
            JujutsuBundle.message("action.bookmark.move.backwards.message", bookmark),
            JujutsuBundle.message("action.bookmark.move.backwards.title"),
            Messages.getWarningIcon()
        ) == Messages.YES
    ) {
        executeMoveToChange(repo, bookmark, changeId, allowBackwards = true)
    }
}
