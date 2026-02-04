package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.invalidate

/**
 * Edit change action.
 * Moves the working copy to the selected commit.
 * Uses `jj edit <change-id>` to make the selected commit the new working copy.
 */
fun editChangeAction(project: Project, logEntry: LogEntry?) =
    nullAndDumbAwareAction(logEntry, "log.action.edit", AllIcons.Actions.Edit) {
        val jujutsuRoot = target.repo
        val id = target.id
        jujutsuRoot.commandExecutor
            .createCommand { edit(id) }
            .onSuccess {
                // The edited change becomes the working copy - select it
                jujutsuRoot.invalidate(select = id)
                log.info("Edited change $id")
            }.onFailureTellUser("log.action.edit.error", project, log)
            .executeAsync()
    }
