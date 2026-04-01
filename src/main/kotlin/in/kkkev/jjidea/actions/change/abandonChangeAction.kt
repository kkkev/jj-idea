package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate

/**
 * Abandon change action.
 * Removes the change from the log with confirmation if it has file modifications or a description.
 */
fun abandonChangeAction(project: Project, entry: LogEntry?) = nullAndDumbAwareAction(
    entry,
    "log.action.abandon",
    AllIcons.General.Delete
) {
    // Check if confirmation is needed
    if (!target.isEmpty || !target.description.empty) {
        // Build confirmation message based on what will be lost
        val confirmMessage = when {
            !target.isEmpty && !target.description.empty -> JujutsuBundle.message("log.action.abandon.confirm.both")
            !target.isEmpty -> JujutsuBundle.message("log.action.abandon.confirm.files")
            else -> JujutsuBundle.message("log.action.abandon.confirm.description")
        }

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

    repo.commandExecutor.createCommand { abandon(target.id) }
        .onSuccess {
            repo.invalidate(select = selectAfter, vfsChanged = true)
            log.info("Abandoned change ${target.id}")
        }.onFailure { tellUser(project, "log.action.abandon.error") }
        .executeAsync()
}
