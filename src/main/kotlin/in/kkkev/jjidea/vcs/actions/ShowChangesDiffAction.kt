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
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RevisionExpression
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.isJujutsu
import `in`.kkkev.jjidea.vcs.jujutsuRepository

/**
 * Action to show diff for selected file changes.
 *
 * Works in three contexts:
 * 1. Working copy changes view: shows diff between @- and @ (editable)
 * 2. Historical log changes view: shows diff between parent and revision (read-only)
 * 3. Project view/editor: shows diff between @- and local file (editable)
 *
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
        val change = e.changes.firstOrNull()
        val logEntry = e.logEntry

        if (change != null) {
            // Changes context (working copy or historical)
            showChangesDiff(project, change, logEntry)
        } else {
            // Project view/editor context - show diff for selected file
            val file = e.file ?: return
            showFileDiff(project, file)
        }
    }

    override fun update(e: AnActionEvent) {
        val hasChanges = e.changes.isNotEmpty()
        val hasJujutsuFile = e.file?.isJujutsu == true

        // Show in changes context OR when in a jj project with a file selected
        e.presentation.isEnabledAndVisible = hasChanges || hasJujutsuFile
    }
}

/**
 * Show diff for a file in project view/editor context.
 * Compares file at @- with the local working copy (editable).
 */
fun showFileDiff(project: Project, file: VirtualFile) {
    val parentRevision = RevisionExpression("@-")
    ApplicationManager.getApplication().executeOnPooledThread {
        val repo = file.jujutsuRepository
        val revisionResult = repo.commandExecutor.show(file.filePath, parentRevision)
        val revisionContent = if (revisionResult.isSuccess) revisionResult.stdout else ""

        ApplicationManager.getApplication().invokeLater {
            val contentFactory = DiffContentFactory.getInstance()
            val localContent = if (file.exists()) {
                contentFactory.create(project, file)
            } else {
                contentFactory.createEmpty()
            }

            val diffRequest = SimpleDiffRequest(
                file.name,
                contentFactory.create(project, revisionContent, file.fileType),
                localContent,
                "${file.name} (@-)",
                "${file.name} (@)"
            )

            DiffManager.getInstance().showDiff(project, diffRequest)
        }
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
            val parentId = logEntry.parentIds.firstOrNull()?.short ?: "parent"
            val currentId = logEntry.id.short
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
