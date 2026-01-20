package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.vcs.jujutsuVcs

/**
 * Edit change action.
 * Moves the working copy to the selected commit.
 * Uses `jj edit <change-id>` to make the selected commit the new working copy.
 */
fun editChangeAction(
    project: Project,
    changeId: ChangeId?
) = nullAndDumbAwareAction(changeId, "log.action.edit", AllIcons.Actions.Edit) {
    project.jujutsuVcs.commandExecutor
        .createCommand { edit(target) }
        .onSuccess {
            project.refreshAfterVcsOperation(selectWorkingCopy = true)
            log.info("Edited change $changeId")
        }.onFailureTellUser("log.action.edit.error", project, log)
        .executeAsync()
}
