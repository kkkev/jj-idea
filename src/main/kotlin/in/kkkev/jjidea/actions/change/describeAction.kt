package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.actions.requestDescription
import `in`.kkkev.jjidea.actions.saveDescriptionToHistory
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.createCommand
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
 * The change id shown in the Describe prompt: deliberately the *short* id, matching every
 * other id the UI displays. `ChangeId.toString()` yields the full id - right for `jj` CLI
 * arguments (GitHub #76), wrong for display (GitHub #76 regression, jj-idea-is97).
 */
internal fun describePromptId(target: LogEntry) = target.id.short

/**
 * Shared implementation behind [describeAction] (context-menu factory, fixed target) and
 * [DescribeChangeAction] (toolbar, reads its target dynamically from the log selection) - both
 * open the same "edit description" dialog and run the same `jj describe`.
 */
internal fun performDescribe(project: Project, target: LogEntry) {
    val jujutsuRoot = target.repo

    jujutsuRoot.createCommand {
        log(target.id, "description")
    }.onSuccess { currentDescription ->
        val newDescription =
            project.requestDescription(
                "dialog.describe.input",
                Description(currentDescription.removeSuffix("\n")),
                describePromptId(target)
            )
                ?: return@onSuccess
        // If that was null, the user cancelled
        jujutsuRoot.createCommand { describe(newDescription, target.id) }
            .onSuccess {
                jujutsuRoot.invalidate()
                project.saveDescriptionToHistory(newDescription)

                describeLog.info("Updated working copy description")
            }.onFailure { tellUser(project, "log.action.describe.error") }
            .executeAsync()
    }.executeAsync()
}
