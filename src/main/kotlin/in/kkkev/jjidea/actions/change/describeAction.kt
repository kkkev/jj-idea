package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.actions.requestDescription
import `in`.kkkev.jjidea.actions.saveDescriptionToHistory
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

private val describeLog = Logger.getInstance("in.kkkev.jjidea.actions.change.describeAction")

/**
 * Describe action.
 * Opens a dialog to edit the description of a revision.
 */
fun describeAction(project: Project, logEntry: LogEntry?) =
    nullAndDumbAwareAction(logEntry, "log.action.describe", JujutsuIcons.Describe) {
        performDescribe(project, target)
    }

/**
 * Shared implementation behind [describeAction] (context-menu factory, fixed target) and
 * [DescribeChangeAction] (toolbar, reads its target dynamically from the log selection) - both
 * open the same "edit description" dialog and run the same `jj describe`.
 */
internal fun performDescribe(project: Project, target: LogEntry) {
    val jujutsuRoot = target.repo
    val commandExecutor = jujutsuRoot.commandExecutor

    commandExecutor.createCommand {
        log(target.id, "description")
    }.onSuccess { currentDescription ->
        val newDescription =
            project.requestDescription(
                "dialog.describe.input",
                Description(currentDescription.removeSuffix("\n")),
                target.id
            )
                ?: return@onSuccess
        // If that was null, the user cancelled
        commandExecutor.createCommand { describe(newDescription, target.id) }
            .onSuccess {
                jujutsuRoot.invalidate()
                project.saveDescriptionToHistory(newDescription)

                describeLog.info("Updated working copy description")
            }.onFailure { tellUser(project, "log.action.describe.error") }
            .executeAsync()
    }.executeAsync()
}
