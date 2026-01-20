package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.vcs.jujutsuVcs

/**
 * Describe action.
 * Opens a dialog to edit the description of a revision.
 */
fun describeAction(
    project: Project,
    revision: Revision?
) = nullAndDumbAwareAction(revision, "log.action.describe", AllIcons.Actions.Edit) {
    val commandExecutor = project.jujutsuVcs.commandExecutor

    commandExecutor
        .createCommand {
            log(target, "description")
        }.onSuccess { currentDescription ->
            val newDescription =
                project.requestDescription("dialog.describe.input", Description(currentDescription))
                    ?: return@onSuccess
            // If that was null, the user cancelled
            commandExecutor
                .createCommand { describe(newDescription, revision!!) }
                .onSuccess {
                    // Refresh all views via state model
                    project.refreshAfterVcsOperation(selectWorkingCopy = false)

                    log.info("Updated working copy description")
                }.onFailureTellUser("log.action.describe.error", project, log)
                .executeAsync()
        }.executeAsync()
}
