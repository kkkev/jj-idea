package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.LogEntry
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

        val confirmTitle = JujutsuBundle.message("log.action.abandon.confirm.title", target.changeId.short)

        // Show yes/no confirmation dialog
        // If user selected No or cancelled, don't proceed
        if (Messages.showYesNoDialog(
                project,
                confirmMessage,
                confirmTitle,
                Messages.getWarningIcon()
            ) != Messages.YES
        ) {
            log.info("User cancelled abandon of ${target.changeId}")
            return@nullAndDumbAwareAction
        }
    }

    // Execute abandon in background thread
    val jujutsuRoot = target.repo
    jujutsuRoot.commandExecutor.createCommand { abandon(target.changeId) }
        .onSuccess {
            jujutsuRoot.invalidate(true)
            log.info("Abandoned change ${target.changeId}")
        }.onFailureTellUser("log.action.abandon.error", project, log)
        .executeAsync()
}
