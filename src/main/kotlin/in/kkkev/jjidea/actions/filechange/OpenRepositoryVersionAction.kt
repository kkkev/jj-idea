package `in`.kkkev.jjidea.actions.filechange

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.vfs.ContentRevisionVirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import javax.swing.Icon

abstract class HistoricalVersionAction(
    resourceKeyPrefix: String, icon: Icon
) : DumbAwareAction(
        JujutsuBundle.message(resourceKeyPrefix),
        JujutsuBundle.message("$resourceKeyPrefix.description"),
        icon
    ) {
    override fun update(e: AnActionEvent) {
        val entry = e.logEntry
        val changes = e.changes

        val visible = entry?.let(::isVisible) ?: false
        // Enabled when at least one file has afterRevision (not deleted)
        val enabled = visible && changes.any { it.afterRevision != null }

        e.presentation.isVisible = visible
        e.presentation.isEnabled = enabled
    }

    // Hidden in working copy context or when no entry
    open fun isVisible(entry: LogEntry) = !entry.isWorkingCopy

    abstract fun actionPerformed(
        project: Project,
        commandExecutor: CommandExecutor,
        logEntry: LogEntry,
        changes: List<Change>
    )

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = e.logEntry ?: return
        val changes = e.changes.filter { it.afterRevision != null }
        if (changes.isEmpty()) return

        runInBackground {
            actionPerformed(project, entry.repo.commandExecutor, entry, changes)
        }
    }
}

/**
 * Open file(s) at a historical revision in a read-only editor tab.
 *
 * Visibility:
 * - Hidden when in working copy context (logEntry.isWorkingCopy = true)
 * - Hidden when no logEntry is present
 *
 * Enabled:
 * - When at least one selected file has afterRevision (not deleted)
 */
class OpenRepositoryVersionAction :
    HistoricalVersionAction("action.open.repository.version", AllIcons.Actions.MenuOpen) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(
        project: Project,
        commandExecutor: CommandExecutor,
        logEntry: LogEntry,
        changes: List<Change>
    ) {
        changes.forEach { change ->
            // TODO Freezing?
            val afterRevision = change.afterRevision ?: return@forEach
            val result = commandExecutor.show(afterRevision.file, logEntry.id)
            val content = if (result.isSuccess) result.stdout else ""
            val cachedRevision = object : ContentRevision {
                override fun getContent() = content
                override fun getFile() = afterRevision.file
                override fun getRevisionNumber() = afterRevision.revisionNumber
            }
            val vFile = ContentRevisionVirtualFile.create(cachedRevision)
            vFile.putUserData(JujutsuDataKeys.VIRTUAL_FILE_LOG_ENTRY, logEntry)
            runLater { FileEditorManager.getInstance(project).openFile(vFile, true) }
        }
    }
}
