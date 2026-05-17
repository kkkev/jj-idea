package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.requestDescription
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories
import `in`.kkkev.jjidea.vcs.possibleJujutsuVcs

/**
 * Opens a dialog to edit the description of the working copy (@).
 * Bound to Ctrl+K to give Jujutsu users a meaningful action on the commit shortcut.
 */
class DescribeWorkingCopyAction : DumbAwareAction(
    JujutsuBundle.message("action.describe.workingcopy"),
    JujutsuBundle.message("action.describe.workingcopy.description"),
    null
) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.possibleJujutsuVcs != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = project.initialisedJujutsuRepositories.firstOrNull() ?: return
        val commandExecutor = repo.commandExecutor

        commandExecutor.createCommand {
            log(WorkingCopy, "description")
        }.onSuccess { currentDescription ->
            val newDescription = project.requestDescription(
                "dialog.describe.workingcopy.input",
                Description(currentDescription.removeSuffix("\n"))
            ) ?: return@onSuccess
            commandExecutor.createCommand { describe(newDescription) }
                .onSuccess { repo.invalidate() }
                .onFailure { tellUser(project, "dialog.describe.error") }
                .executeAsync()
        }.executeAsync()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
