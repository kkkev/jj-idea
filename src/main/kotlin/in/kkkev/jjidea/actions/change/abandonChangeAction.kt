package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate

/**
 * Abandon change action.
 * Removes the change from the log with confirmation if it has file modifications, a description,
 * or a local bookmark — `jj abandon` deletes local bookmarks pointing at the abandoned revision
 * (jj-idea-940s, GitHub #129), so an empty, undescribed change with a bookmark still needs a
 * warning or the bookmark disappears silently.
 */
fun abandonChangeAction(project: Project, entry: LogEntry?) = nullAndDumbAwareAction(
    entry,
    "log.action.abandon",
    AllIcons.General.Delete
) {
    // Check if confirmation is needed
    abandonConfirmMessage(target)?.let { confirmMessage ->
        val confirmTitle = JujutsuBundle.message("log.action.abandon.confirm.title", target.id.short)

        // Show yes/no confirmation dialog
        // If user selected No or cancelled, don't proceed
        if (Messages.showYesNoDialog(
                project,
                confirmMessage,
                confirmTitle,
                Messages.getWarningIcon()
            ) != Messages.YES
        ) {
            log.info("User cancelled abandon of ${target.id}")
            return@nullAndDumbAwareAction
        }
    }

    // Select a parent after abandon; if abandoning the WC, jj creates a new one automatically
    val repo = target.repo
    val selectAfter = if (target.isWorkingCopy) WorkingCopy else target.parentIds.firstOrNull() ?: WorkingCopy

    repo.createCommand { abandon(target.id) }
        .onSuccess {
            invalidate(select = selectAfter, vfsChanged = true)
            log.info("Abandoned change ${target.id}")
        }.onFailure { tellUser("log.action.abandon.error") }
        .addUndoTracking("log.action.abandon.undo")
        .executeAsync()
}

/**
 * Builds the abandon-confirmation dialog message for [entry], or `null` if abandoning it is safe
 * without confirmation (no file modifications, no description, and no local bookmark that would
 * be deleted along with it).
 *
 * Only local, non-deleted bookmarks are listed: `jj abandon` deletes local bookmarks pointing at
 * the abandoned revision, but leaves remote-tracking bookmarks (`name@remote`) alone.
 */
internal fun abandonConfirmMessage(entry: LogEntry): String? {
    val localBookmarks = entry.bookmarks.filter { !it.isRemote && !it.deleted }

    val reasons = buildList {
        if (!entry.isEmpty) add(JujutsuBundle.message("log.action.abandon.confirm.reason.files"))
        if (!entry.description.empty) add(JujutsuBundle.message("log.action.abandon.confirm.reason.description"))
        if (localBookmarks.isNotEmpty()) {
            val names = localBookmarks.joinToString(", ") { it.localName }
            val key = if (localBookmarks.size == 1) {
                "log.action.abandon.confirm.reason.bookmark"
            } else {
                "log.action.abandon.confirm.reason.bookmarks"
            }
            add(JujutsuBundle.message(key, names))
        }
    }

    if (reasons.isEmpty()) return null

    val bulletList = reasons.joinToString("\n") { "• $it" }
    return JujutsuBundle.message("log.action.abandon.confirm.message", bulletList)
}
