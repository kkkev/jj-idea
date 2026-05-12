package `in`.kkkev.jjidea.actions.filechange

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.actions.logEntryForFile
import `in`.kkkev.jjidea.jj.diffRequest
import `in`.kkkev.jjidea.jj.fileAtWorkingCopy
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.fileAtVersion
import `in`.kkkev.jjidea.vcs.filePath

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
    override fun actionPerformed(e: AnActionEvent) {
        val logEntry = e.logEntryForFile ?: return
        val repo = logEntry.repo

        runInBackground {
            val requests = e.changes.mapNotNull { change ->
                change.after?.let { after ->
                    val filePath = after.filePath
                    val localSide = repo.createDiffSideFor(filePath.fileAtWorkingCopy)
                    val historicalSide = repo.createDiffSideFor(after)
                    diffRequest(filePath.name, historicalSide, localSide)
                }
            }.ifEmpty {
                // Editor context: no changes in DataSink, use the file directly
                e.file?.let { f ->
                    listOf(
                        diffRequest(
                            f.name,
                            repo.createDiffSideFor(f.fileAtVersion),
                            repo.createDiffSideFor(f.filePath.fileAtWorkingCopy)
                        )
                    )
                } ?: emptyList()
            }

            if (requests.isNotEmpty()) {
                runLater {
                    val chain = SimpleDiffRequestChain(requests)
                    DiffManager.getInstance().showDiff(repo.project, chain, DiffDialogHints.DEFAULT)
                }
            }
        }
    }
}
