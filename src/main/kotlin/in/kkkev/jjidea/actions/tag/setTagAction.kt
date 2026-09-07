package `in`.kkkev.jjidea.actions.tag

import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.services.withUndoBalloon
import `in`.kkkev.jjidea.util.runLater

fun setTagAction(entry: LogEntry?) =
    nullAndDumbAwareAction(entry, "action.tag.set", JujutsuIcons.TagAdd) {
        TagNameDialog(target.repo) { tag ->
            executeSetTag(target.repo, tag, target.id, allowMove = false)
        }.show()
    }

/**
 * Runs `jj tag set`, with undo tracking and an undo balloon on success. Shared by [setTagAction]'s
 * dialog path and [in.kkkev.jjidea.ui.dnd.DropPerformers]'s drag-and-drop path (jj-idea-ibth) -
 * the drag gesture calls this with `allowMove = false` and reuses the `--allow-move`
 * confirm-and-retry flow below verbatim, same as the dialog path does.
 */
internal fun executeSetTag(repo: JujutsuRepository, tag: Tag, targetId: ChangeId, allowMove: Boolean) {
    repo.commandExecutor.withUndoTracking()
        .createCommand { tagSet(tag, targetId, allowMove) }
        .onSuccess { repo.invalidate() }
        .onFailure {
            if (!allowMove && exitCode == 1 && stderr.contains("allow-move")) {
                runLater { promptMove(repo, tag, targetId) }
            } else {
                tellUser(repo.project, "action.tag.set.error")
            }
        }
        .withUndoBalloon(repo.project, repo, "action.tag.set.undo")
        .executeAsync()
}

private fun promptMove(repo: JujutsuRepository, tag: Tag, targetId: ChangeId) {
    if (Messages.showYesNoDialog(
            repo.project,
            JujutsuBundle.message("action.tag.set.move.message", tag.name),
            JujutsuBundle.message("action.tag.set.move.title", tag.name),
            Messages.getWarningIcon()
        ) == Messages.YES
    ) {
        executeSetTag(repo, tag, targetId, allowMove = true)
    }
}
