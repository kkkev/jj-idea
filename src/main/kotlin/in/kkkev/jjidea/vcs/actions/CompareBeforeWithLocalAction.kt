package `in`.kkkev.jjidea.vcs.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.LocalFileSystem
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision

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
class CompareBeforeWithLocalAction : DumbAwareAction(
    JujutsuBundle.message("action.compare.before.with.local"),
    JujutsuBundle.message("action.compare.before.with.local.description"),
    AllIcons.Actions.Diff
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val logEntry = e.logEntry ?: return
        val parentId = logEntry.parentIds.firstOrNull() ?: return

        compareChangesWithLocal(project, e.changes, logEntry.repo, parentId)
    }

    private fun compareChangesWithLocal(
        project: Project,
        changes: List<Change>,
        repo: JujutsuRepository,
        revision: Revision
    ) {
        val validChanges = changes.filter { it.beforeRevision != null }
        if (validChanges.isEmpty()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val requests = validChanges.mapNotNull { change ->
                val filePath = change.beforeRevision?.file ?: return@mapNotNull null

                val revisionResult = repo.commandExecutor.show(filePath, revision)
                val revisionContent = if (revisionResult.isSuccess) revisionResult.stdout else ""

                val contentFactory = DiffContentFactory.getInstance()
                val localFile = LocalFileSystem.getInstance().findFileByPath(filePath.path)
                val localContent = if (localFile?.exists() == true) {
                    contentFactory.create(project, localFile)
                } else {
                    contentFactory.createEmpty()
                }

                SimpleDiffRequest(
                    filePath.name,
                    contentFactory.create(project, revisionContent, filePath.fileType),
                    localContent,
                    "${filePath.name} (${revision.short})",
                    "${filePath.name} (${JujutsuBundle.message("diff.label.local")})"
                )
            }

            if (requests.isNotEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    val chain = SimpleDiffRequestChain(requests)
                    DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val logEntry = e.logEntry

        // Only visible in historical log context with parents
        val visible = logEntry != null && !logEntry.isWorkingCopy && logEntry.parentIds.isNotEmpty()
        val enabled = visible && e.changes.any { it.beforeRevision != null }

        e.presentation.isVisible = visible
        e.presentation.isEnabled = enabled
    }
}
