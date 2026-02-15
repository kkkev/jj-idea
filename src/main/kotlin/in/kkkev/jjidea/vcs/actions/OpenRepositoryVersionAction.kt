package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.testFramework.LightVirtualFile
import `in`.kkkev.jjidea.JujutsuBundle

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
class OpenRepositoryVersionAction : DumbAwareAction(
    JujutsuBundle.message("action.open.repository.version"),
    JujutsuBundle.message("action.open.repository.version.description"),
    AllIcons.Actions.MenuOpen
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
            changes.forEach { change ->
                val filePath = change.afterRevision?.file ?: return@forEach
                val result = repo.commandExecutor.show(filePath, revision)
                val content = if (result.isSuccess) result.stdout else ""

                ApplicationManager.getApplication().invokeLater {
                    val fileType = FileTypeRegistry.getInstance().getFileTypeByFileName(filePath.name)
                    val lightFile = LightVirtualFile(
                        "${filePath.name} (${revision.short})",
                        fileType,
                        content
                    )
                    lightFile.isWritable = false
                    FileEditorManager.getInstance(project).openFile(lightFile, true)
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
