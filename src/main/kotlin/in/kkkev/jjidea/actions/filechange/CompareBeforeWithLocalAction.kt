package `in`.kkkev.jjidea.actions.filechange

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.diffRequest
import `in`.kkkev.jjidea.jj.fileAtWorkingCopy
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Compare file(s) from the parent of a historical revision with local working copy.
 *
 * This action is only for historical log context - it answers the question:
 * "How does my local file differ from this historical revision's parent?"
 *
 * Visibility:
 * - Hidden when in working copy context (logEntry.isWorkingCopy = true)
 * - Hidden when the entry has no parents
 * - Hidden when not in log context (no logEntry)
 *
 * Enabled:
 * - When at least one selected file has beforeRevision (not added)
 */
class CompareBeforeWithLocalAction :
    HistoricalVersionAction("action.compare.before.with.local", AllIcons.Actions.Diff) {
    override fun actionPerformed(e: AnActionEvent) {
        val logEntry = e.logEntry ?: return
        val repo = logEntry.repo

        runInBackground {
            val requests = e.changes.mapNotNull { change ->
                change.before?.let { before ->
                    val filePath = before.filePath
                    val localDiffSide = repo.createDiffSideFor(filePath.fileAtWorkingCopy)
                    val parentDiffSide = repo.createDiffSideFor(before)

                    diffRequest(filePath.name, parentDiffSide, localDiffSide)
                }
            }

            if (requests.isNotEmpty()) {
                runLater {
                    val chain = SimpleDiffRequestChain(requests)
                    DiffManager.getInstance().showDiff(repo.project, chain, DiffDialogHints.DEFAULT)
                }
            }
        }
    }

    // Only visible in historical log context with parents
    override fun isVisible(entry: LogEntry) = super.isVisible(entry) && entry.parentIds.isNotEmpty()
}
