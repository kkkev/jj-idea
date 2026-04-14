package `in`.kkkev.jjidea.actions.filechange

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.LocalFileSystem
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.util.runLater

/**
 * Compare file(s) at a historical revision with the local working copy.
 *
 * Visibility:
 * - Hidden when in working copy context (logEntry.isWorkingCopy = true)
 * - Hidden when no logEntry is present
 *
 * Enabled:
 * - When at least one selected file has afterRevision (not deleted)
 */
class CompareWithLocalAction : HistoricalVersionAction("action.compare.with.local", AllIcons.Actions.Diff) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(
        project: Project,
        commandExecutor: CommandExecutor,
        logEntry: LogEntry,
        changes: List<Change>
    ) {
        val changeId = logEntry.id
        val requests = changes.mapNotNull { change ->
            val filePath = change.afterRevision?.file ?: return@mapNotNull null
            val result = commandExecutor.show(filePath, changeId)
            val content = if (result.isSuccess) result.stdout else ""

            val contentFactory = DiffContentFactory.getInstance()
            val localFile = LocalFileSystem.getInstance().findFileByPath(filePath.path)
            val localContent = if (localFile?.exists() == true) {
                contentFactory.create(project, localFile)
            } else {
                contentFactory.createEmpty()
            }

            SimpleDiffRequest(
                filePath.name,
                contentFactory.create(project, content, filePath.fileType),
                localContent,
                "${filePath.name} (${changeId.short})",
                "${filePath.name} (${JujutsuBundle.message("diff.label.local")})"
            )
        }

        if (requests.isNotEmpty()) {
            runLater {
                val chain = SimpleDiffRequestChain(requests)
                DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
            }
        }
    }
}
