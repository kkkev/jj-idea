package `in`.kkkev.jjidea.actions.filechange

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.actions.logEntryForFile
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.diffRequest
import `in`.kkkev.jjidea.jj.fileAt
import `in`.kkkev.jjidea.ui.components.Filter
import `in`.kkkev.jjidea.ui.components.RevisionSelectorPopup
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.filePath

/**
 * Compare file(s) from the parent of a historical revision with a user-chosen revision.
 *
 * This action is only for historical log context - it answers the question:
 * "How does this historical revision's parent differ from some other commit I pick?"
 *
 * The parent is always shown on the left and the chosen revision on the right - matching
 * [CompareBeforeWithLocalAction], but the opposite convention from
 * [in.kkkev.jjidea.actions.file.CompareFileWithBranchAction], where the chosen revision is
 * always on the left.
 *
 * Visibility:
 * - Hidden when in working copy context (logEntry.isWorkingCopy = true)
 * - Hidden when the entry has no parents
 * - Hidden when not in log context (no logEntry)
 *
 * Enabled:
 * - When at least one selected file has beforeRevision (not added)
 */
class CompareBeforeWithBranchAction :
    HistoricalVersionAction("action.compare.before.with.branch", AllIcons.Actions.Diff) {
    override fun actionPerformed(e: AnActionEvent) {
        val logEntry = e.logEntryForFile ?: return
        val repo = logEntry.repo
        val changes = e.changes
        val file = e.file

        runInBackground {
            RevisionSelectorPopup.show(
                "action.compare.before.with.branch.popup.title",
                repo,
                Filter(true, true)
            ) { chosen ->
                showDiff(repo, logEntry, changes, file, chosen)
            }
        }
    }

    private fun showDiff(
        repo: JujutsuRepository,
        logEntry: LogEntry,
        changes: List<FileChange>,
        file: VirtualFile?,
        chosenRevision: Revision
    ) {
        runInBackground {
            // Locate change id in case revision is a bookmark or other expression
            val chosenChangeId = runCatching { repo.getLogEntry(chosenRevision).id }.getOrNull()
            if (chosenChangeId == null) {
                runLater {
                    Messages.showErrorDialog(
                        repo.project,
                        JujutsuBundle.message("action.compare.branch.resolve.error.message", chosenRevision.toString()),
                        JujutsuBundle.message("action.compare.branch.resolve.error.title")
                    )
                }
                return@runInBackground
            }

            val requests = changes.mapNotNull { change ->
                change.before?.let { before ->
                    val filePath = before.filePath
                    val parentDiffSide = repo.createDiffSideFor(before)
                    val chosenDiffSide = repo.createDiffSideFor(filePath.fileAt(chosenChangeId))

                    diffRequest(
                        JujutsuBundle.message("diff.title.compare.before", filePath.name, chosenRevision.toString()),
                        parentDiffSide,
                        chosenDiffSide
                    )
                }
            }.ifEmpty {
                // Editor context: no changes in DataSink, use the file and entry's parent content locator
                file?.let { f ->
                    listOf(
                        diffRequest(
                            JujutsuBundle.message("diff.title.compare.before", f.name, chosenRevision.toString()),
                            repo.createDiffSideFor(f.filePath.fileAt(logEntry.parentContentLocator)),
                            repo.createDiffSideFor(f.filePath.fileAt(chosenChangeId))
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

    // Only visible in historical log context with parents
    override fun isVisible(entry: LogEntry) = super.isVisible(entry) && entry.parentIds.isNotEmpty()
}
