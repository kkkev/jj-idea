package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.components.RevisionSelectorPopup

/**
 * Move bookmark action.
 * Shows a bookmark selector popup, then moves the selected bookmark to the target change.
 * If JJ refuses because the move is backwards/sideways, prompts for confirmation and retries
 * with --allow-backwards.
 */
fun moveBookmarkAction(entry: LogEntry?) = nullAndDumbAwareAction(
    entry,
    "action.bookmark.move",
    AllIcons.Actions.MoveUp
) {
    val repo = target.repo
    RevisionSelectorPopup.show(
        "action.bookmark,move.popup.title",
        repo,
        RevisionSelectorPopup.Filter(false, false)
    ) { revision ->
        (revision as? Bookmark)?.let { bookmark ->
            repo.commandExecutor
                .createCommand { bookmarkSet(bookmark, target.id) }
                .onSuccess {
                    log.info("Moved bookmark $bookmark to ${target.id}")
                    repo.invalidate()
                }
                .onFailure {
                    if (exitCode == 1 && stderr.contains("backwards or sideways")) {
                        if (Messages.showYesNoDialog(
                                repo.project,
                                JujutsuBundle.message("action.bookmark.move.backwards.message", bookmark),
                                JujutsuBundle.message("action.bookmark.move.backwards.title"),
                                Messages.getWarningIcon()
                            ) == Messages.YES
                        ) {
                            repo.commandExecutor
                                .createCommand { bookmarkSet(bookmark, target.id, allowBackwards = true) }
                                .onSuccess {
                                    log.info("Force-moved bookmark $bookmark to ${target.id}")
                                    repo.invalidate()
                                }
                                .onFailure { tellUser(repo.project, "action.bookmark.move.error") }
                                .executeAsync()
                        }
                    } else {
                        tellUser(repo.project, "action.bookmark.move.error")
                    }
                }
                .executeAsync()
        }
    }
}
