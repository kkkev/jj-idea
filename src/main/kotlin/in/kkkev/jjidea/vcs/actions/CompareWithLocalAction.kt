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
import com.intellij.openapi.vfs.LocalFileSystem
import `in`.kkkev.jjidea.JujutsuBundle

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
class CompareWithLocalAction : DumbAwareAction(
    JujutsuBundle.message("action.compare.with.local"),
    JujutsuBundle.message("action.compare.with.local.description"),
    AllIcons.Actions.Diff
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = e.logEntry ?: return
        val changes = e.changes.filter { it.afterRevision != null }
        if (changes.isEmpty()) return

        val repo = entry.repo
        val revision = entry.id

        ApplicationManager.getApplication().executeOnPooledThread {
            val requests = changes.mapNotNull { change ->
                val filePath = change.afterRevision?.file ?: return@mapNotNull null
                val relPath = repo.getRelativePath(filePath)

                val revisionResult = repo.commandExecutor.show(relPath, revision)
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
        val entry = e.logEntry
        val changes = e.changes

        // Hidden in working copy context or when no entry
        val visible = entry != null && !entry.isWorkingCopy
        // Enabled when at least one file has afterRevision (not deleted)
        val enabled = visible && changes.any { it.afterRevision != null }

        e.presentation.isVisible = visible
        e.presentation.isEnabled = enabled
    }
}
