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
        jujutsuRoot.commandExecutor
            .createCommand { edit(target.changeId) }
            .onSuccess {
                jujutsuRoot.invalidate()
                log.info("Edited change ${target.changeId}")
            }.onFailureTellUser("log.action.edit.error", project, log)
            .executeAsync()
    }
