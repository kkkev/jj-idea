package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate

/**
 * Describe action.
 * Opens a dialog to edit the description of a revision.
 */
fun describeAction(project: Project, logEntry: LogEntry?) =
    nullAndDumbAwareAction(logEntry, "log.action.describe", AllIcons.Actions.Edit) {
        val jujutsuRoot = target.repo
        val commandExecutor = jujutsuRoot.commandExecutor

        commandExecutor.createCommand {
            log(target.changeId, "description")
        }.onSuccess { currentDescription ->
            val newDescription =
                project.requestDescription("dialog.describe.input", Description(currentDescription))
                    ?: return@onSuccess
            // If that was null, the user cancelled
            commandExecutor.createCommand { describe(newDescription, target.changeId) }
                .onSuccess {
                    jujutsuRoot.invalidate()

                    log.info("Updated working copy description")
                }.onFailureTellUser("log.action.describe.error", project, log)
                .executeAsync()
        }.executeAsync()
    }
