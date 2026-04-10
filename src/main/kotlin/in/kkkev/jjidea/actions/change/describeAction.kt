package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.actions.requestDescription
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

/**
 * Describe action.
 * Opens a dialog to edit the description of a revision.
 */
fun describeAction(project: Project, logEntry: LogEntry?) =
    nullAndDumbAwareAction(logEntry, "log.action.describe", JujutsuIcons.Describe) {
        val jujutsuRoot = target.repo
        val commandExecutor = jujutsuRoot.commandExecutor

        commandExecutor.createCommand {
            log(target.id, "description")
        }.onSuccess { currentDescription ->
            val newDescription =
                project.requestDescription("dialog.describe.input", Description(currentDescription.removeSuffix("\n")))
                    ?: return@onSuccess
            // If that was null, the user cancelled
            commandExecutor.createCommand { describe(newDescription, target.id) }
                .onSuccess {
                    jujutsuRoot.invalidate()

                    log.info("Updated working copy description")
                }.onFailure { tellUser(project, "log.action.describe.error") }
                .executeAsync()
        }.executeAsync()
    }
