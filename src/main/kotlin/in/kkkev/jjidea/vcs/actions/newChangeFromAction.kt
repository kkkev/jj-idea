package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.vcs.jujutsuVcs

/**
 * Create new change from the selected commit.
 * Uses `jj new <change-id>` to create a new working copy based on this commit.
 */
fun newChangeFromAction(project: Project, changeIds: List<ChangeId>) =
    emptyAndDumbAwareAction(
        changeIds,
        (if (changeIds.size == 1) "log.action.new.from.singular" else "log.action.new.from.plural"),
        AllIcons.General.Add
    ) {
        // Show modal dialog to get description for the new change
        // Null means that the user cancelled when description was requested
        val description = project.requestDescription("dialog.newchange.input")
            ?: return@emptyAndDumbAwareAction

        project.jujutsuVcs.commandExecutor
            .createCommand {
                new(description = description, parentRevisions = changeIds)
            }
            .onSuccess {
                // Refresh all views via state model
                project.refreshAfterVcsOperation(selectWorkingCopy = true)

                log.info("Created new change from $changeIds with description: $description")
            }
            .onFailureTellUser("log.action.new.error", project, log)
            .executeAsync()
    }