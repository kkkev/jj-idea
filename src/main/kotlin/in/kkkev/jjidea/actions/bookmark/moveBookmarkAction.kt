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
import `in`.kkkev.jjidea.ui.services.withUndoBalloon
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

/**
 * Runs `jj bookmark set`, with undo tracking and an undo balloon on success. Shared by
 * [moveBookmarkAction]'s dialog path and [in.kkkev.jjidea.ui.dnd.DropPerformers]'s drag-and-drop
 * path (jj-idea-ibth) - the drag gesture calls this with `allowBackwards = false` and reuses the
 * "backwards or sideways" confirm-and-retry flow below verbatim, same as the dialog path does.
 */
internal fun executeMove(
    repo: JujutsuRepository,
    bookmark: Bookmark,
    targetId: ChangeId,
    allowBackwards: Boolean
) {
    repo.commandExecutor.withUndoTracking()
        .createCommand { bookmarkSet(bookmark.name, targetId, allowBackwards) }
        .onSuccess { repo.invalidate(select = targetId) }
        .onFailure {
            if (!allowBackwards && exitCode == 1 && stderr.contains("backwards or sideways")) {
                // Not necessarily a race - the drag gesture (jj-idea-ibth) never pre-classifies
                // direction the way the dialog above does, so this also fires for a plain,
                // deliberate backward/sideways drag. The message below must therefore stand on
                // its own and not claim a dialog or a race that may not have happened.
                runLater { promptBackwardMove(repo.project, repo, bookmark, targetId) }
            } else {
                tellUser(repo.project, "action.bookmark.move.error")
            }
        }
        .withUndoBalloon(repo.project, repo, "action.bookmark.move.undo")
        .executeAsync()
}

private fun promptBackwardMove(
    project: Project,
    repo: JujutsuRepository,
    bookmark: Bookmark,
    targetId: ChangeId
) {
    if (Messages.showYesNoDialog(
            project,
            JujutsuBundle.message("action.bookmark.move.backwards.message", bookmark.name),
            JujutsuBundle.message("action.bookmark.move.backwards.title"),
            Messages.getWarningIcon()
        ) == Messages.YES
    ) {
        executeMove(repo, bookmark, targetId, allowBackwards = true)
    }
}
