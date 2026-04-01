package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.actions.requestDescription
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

/**
 * Create new change from the selected commit.
 * Uses `jj new <change-id>` to create a new working copy based on this commit.
 */
fun newChangeFromAction(project: Project, repo: JujutsuRepository?, parentRevisions: List<Revision>) =
    nullAndDumbAwareAction(
        repo,
        (if (parentRevisions.size == 1) "log.action.new.from.singular" else "log.action.new.from.plural"),
        JujutsuIcons.NewChange
    ) {
        // Show modal dialog to get description for the new change
        // Null means that the user cancelled when description was requested
        val description = project.requestDescription("dialog.newchange.input") ?: return@nullAndDumbAwareAction

        target.commandExecutor.createCommand {
            new(description = description, parentRevisions = parentRevisions)
        }.onSuccess {
            // The new change becomes the working copy - select it
            target.invalidate(select = WorkingCopy, vfsChanged = true)
            log.info("Created new change from $parentRevisions with description: $description")
        }.onFailure { tellUser(project, "log.action.new.error") }
            .executeAsync()
    }
