package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate

/**
 * Create new change from the selected commit.
 * Uses `jj new <change-id>` to create a new working copy based on this commit.
 */
fun newChangeFromAction(project: Project, repo: JujutsuRepository?, parentRevisions: List<Revision>) =
    nullAndDumbAwareAction(
        repo,
        (if (parentRevisions.size == 1) "log.action.new.from.singular" else "log.action.new.from.plural"),
        AllIcons.General.Add
    ) {
        // Show modal dialog to get description for the new change
        // Null means that the user cancelled when description was requested
        val description = project.requestDescription("dialog.newchange.input") ?: return@nullAndDumbAwareAction

        target.commandExecutor.createCommand {
            new(description = description, parentRevisions = parentRevisions)
        }.onSuccess {
            // The new change becomes the working copy - select it
            target.invalidate(select = WorkingCopy)
            log.info("Created new change from $parentRevisions with description: $description")
        }.onFailureTellUser("log.action.new.error", project, log)
            .executeAsync()
    }
