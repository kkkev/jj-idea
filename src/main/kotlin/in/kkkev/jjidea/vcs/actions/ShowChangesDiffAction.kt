package `in`.kkkev.jjidea.vcs.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
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
import `in`.kkkev.jjidea.jj.LogEntry

/**
 * Action to show diff for selected file changes.
 * Registered in plugin.xml and used via ActionManager lookup.
 */
class ShowChangesDiffAction : DumbAwareAction(
    JujutsuBundle.message("action.show.diff"),
    null,
    AllIcons.Actions.Diff
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val change = e.changes.firstOrNull() ?: return
        val logEntry = e.logEntry

        showChangesDiff(project, change, logEntry)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && e.changes.isNotEmpty()
    }
}

/**
 * Show diff for a file change.
 *
 * Context logic:
 * - No logEntry or isWorkingCopy=true → working copy context (mutable).
 *   Uses LocalFileSystem VirtualFile for editable after-side, labels (@-) / (@).
 * - Historical (logEntry present, isWorkingCopy=false) → read-only context.
 *   Uses revision content for both sides, labels (parentId) / (currentId).
 *
 * @param project The current project
 * @param change The file change to show diff for
 * @param logEntry Optional log entry for context. Null means working copy context.
 */
fun showChangesDiff(project: Project, change: Change, logEntry: LogEntry?) {
    val isWorkingCopyContext = logEntry == null || logEntry.isWorkingCopy

    ApplicationManager.getApplication().executeOnPooledThread {
        val beforePath = change.beforeRevision?.file
        val afterPath = change.afterRevision?.file
        val fileName = afterPath?.name ?: beforePath?.name ?: JujutsuBundle.message("diff.title.unknown")

        // Load before content (always from revision)
        val beforeContent = change.beforeRevision?.content ?: ""

        // For working copy context, try to get VirtualFile for editable after-side
        val afterVirtualFile = if (isWorkingCopyContext) {
            afterPath?.let { LocalFileSystem.getInstance().findFileByPath(it.path) }
        } else {
            null
        }

        // Load after content from revision if not using VirtualFile
        val afterContent = if (afterVirtualFile == null) {
            change.afterRevision?.content ?: ""
        } else {
            null
        }

        // Build labels based on context
        val (beforeLabel, afterLabel) = if (isWorkingCopyContext) {
            val beforeName = beforePath?.name ?: JujutsuBundle.message("diff.title.before")
            val afterName = afterPath?.name ?: JujutsuBundle.message("diff.title.after")
            "$beforeName (@-)" to "$afterName (@)"
        } else {
            val parentId = logEntry?.parentIds?.firstOrNull()?.short ?: "parent"
            val currentId = logEntry?.id?.short ?: "current"
            val beforeName = beforePath?.name ?: JujutsuBundle.message("diff.title.before")
            val afterName = afterPath?.name ?: JujutsuBundle.message("diff.title.after")
            "$beforeName ($parentId)" to "$afterName ($currentId)"
        }

        ApplicationManager.getApplication().invokeLater {
            val contentFactory = DiffContentFactory.getInstance()
            val diffManager = DiffManager.getInstance()

            val content1 = if (beforePath != null && beforeContent.isNotEmpty()) {
                contentFactory.create(project, beforeContent, beforePath.fileType)
            } else {
                contentFactory.createEmpty()
            }

            val content2 = when {
                afterVirtualFile != null && afterVirtualFile.exists() ->
                    contentFactory.create(project, afterVirtualFile)
                afterPath != null && afterContent != null && afterContent.isNotEmpty() ->
                    contentFactory.create(project, afterContent, afterPath.fileType)
                else -> contentFactory.createEmpty()
            }

            val diffRequest = SimpleDiffRequest(fileName, content1, content2, beforeLabel, afterLabel)
            diffManager.showDiff(project, diffRequest)
        }
    }
}
