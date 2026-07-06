package `in`.kkkev.jjidea.actions.filechange

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.fileList
import `in`.kkkev.jjidea.actions.filesFor
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

class ShowDiffInNewTabAction : DumbAwareAction(
    JujutsuBundle.message("action.show.diff.new.tab"),
    JujutsuBundle.message("action.show.diff.new.tab.description"),
    AllIcons.Actions.OpenNewTab
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val changes = e.changes // EDT capture — cheap (data key access only)
        val fileList = e.fileList // EDT capture — cheap (data key access only)
        val logEntry = if (changes.isEmpty() && fileList.isNullOrEmpty()) e.logEntry else null
        runInBackground {
            // filesFor may run `jj log` when resolving from changes — must stay off the EDT
            val files = project.filesFor(fileList, changes)
            if (logEntry != null) {
                val fileChanges = logEntry.repo.logService.getFileChanges(logEntry).getOrElse { emptyList() }
                val requests = buildDiffRequests(project, fileChanges, emptyList())
                if (requests.isNotEmpty()) runLater { openDiffChain(project, requests, logEntry.id.short) }
            } else {
                val requests = buildDiffRequests(project, changes, files)
                if (requests.isEmpty()) return@runInBackground
                val tabTitle = if (requests.size == 1) requests.first().title ?: "Diff" else "${requests.size} files"
                runLater { openDiffChain(project, requests, tabTitle) }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.changes.isNotEmpty() || e.repoForFile != null || e.logEntry != null
    }
}
