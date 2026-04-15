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
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.actions.JujutsuDataKeys.DiffContentInfo
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
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
 *
 * For project view/editor "show diff" functionality, use [ShowChangesDiffAction] instead.
 */
class CompareBeforeWithLocalAction :
    HistoricalVersionAction("action.compare.before.with.local", AllIcons.Actions.Diff) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(
        project: Project,
        commandExecutor: CommandExecutor,
        logEntry: LogEntry,
        changes: List<Change>
    ) {
        // TODO This only compares against the first parent, which may be incorrect for a merge
        val parentIdentifiers = logEntry.parentIdentifiers.firstOrNull() ?: return
        compareChangesWithLocal(
            project,
            changes,
            commandExecutor,
            logEntry.repo,
            parentIdentifiers.changeId,
            parentIdentifiers.commitId
        )
    }

    private fun compareChangesWithLocal(
        project: Project,
        changes: List<Change>,
        commandExecutor: CommandExecutor,
        repo: JujutsuRepository,
        parentRevision: Revision,
        parentCommitId: CommitId?
    ) {
        val validChanges = changes.filter { it.beforeRevision != null }
        if (validChanges.isEmpty()) return

        runInBackground {
            val requests = validChanges.mapNotNull { change ->
                val filePath = change.beforeRevision?.file ?: return@mapNotNull null

                val revisionResult = commandExecutor.show(filePath, parentRevision)
                val revisionContent = if (revisionResult.isSuccess) revisionResult.stdout else ""

                val contentFactory = DiffContentFactory.getInstance()
                val localFile = LocalFileSystem.getInstance().findFileByPath(filePath.path)
                val localContent = if (localFile?.exists() == true) {
                    contentFactory.create(project, localFile)
                } else {
                    contentFactory.createEmpty()
                }

                val historicalContent = contentFactory.create(project, revisionContent, filePath.fileType)
                historicalContent.putUserData(
                    JujutsuDataKeys.DIFF_CONTENT_INFO,
                    DiffContentInfo(repo, filePath, parentCommitId)
                )

                SimpleDiffRequest(
                    filePath.name,
                    historicalContent,
                    localContent,
                    "${filePath.name} (${parentRevision.short})",
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

    // Only visible in historical log context with parents
    override fun isVisible(entry: LogEntry) = super.isVisible(entry) && entry.parentIds.isNotEmpty()
}
