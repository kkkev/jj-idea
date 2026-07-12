package `in`.kkkev.jjidea.actions.change

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.filechange.buildDiffRequests
import `in`.kkkev.jjidea.actions.filechange.openDiffChain
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Compare the whole working copy with a selected commit.
 * Enumerates every file that differs between the commit and `@` and opens them all in a single,
 * reusable diff editor tab, with the working-copy side editable. Mirrors Git's
 * "Compare with Local" commit-level action.
 */
fun compareWithWorkingCopyAction(project: Project, logEntry: LogEntry?) =
    nullAndDumbAwareAction(logEntry, "log.action.compare.with.working.copy", AllIcons.Actions.Diff) {
        val repo = target.repo
        val id = target.id
        runInBackground {
            val changes = repo.logService.getFileChangesBetween(id, WorkingCopy).getOrNull().orEmpty()
            if (changes.isEmpty()) {
                runLater {
                    JujutsuNotifications.notify(
                        project,
                        JujutsuBundle.message("log.action.compare.with.working.copy.empty.title"),
                        JujutsuBundle.message("log.action.compare.with.working.copy.empty.message"),
                        NotificationType.INFORMATION
                    )
                }
            } else {
                // buildDiffRequests runs jj commands synchronously (show/log), so it must stay
                // off the EDT — only the resulting UI-facing openDiffChain call is dispatched via runLater.
                val requests = buildDiffRequests(project, changes, emptyList())
                runLater {
                    openDiffChain(project, requests, JujutsuBundle.message("diff.tab.compare.working.copy", id.short))
                }
            }
        }
    }
