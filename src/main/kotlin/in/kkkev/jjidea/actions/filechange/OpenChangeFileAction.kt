package `in`.kkkev.jjidea.actions.filechange

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.files
import `in`.kkkev.jjidea.vcs.filePath

/**
 * Action to open selected file(s) in the editor.
 * Registered in plugin.xml and used via ActionManager lookup.
 * Has F4 and Enter shortcuts.
 */
class OpenChangeFileAction : DumbAwareAction(
    JujutsuBundle.message("action.open.file"),
    null,
    AllIcons.Actions.EditSource
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.files.takeIf { it.isNotEmpty() }
            ?: e.changes.mapNotNull { it.filePath?.virtualFile }

        if (files.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            val fileEditorManager = FileEditorManager.getInstance(project)
            files.forEach { file ->
                fileEditorManager.openFile(file, true)
                OpenFileDescriptor(project, file).navigate(true)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val hasFiles = e.files.isNotEmpty() ||
            e.changes.any { it.filePath?.virtualFile != null }
        e.presentation.isEnabled = e.project != null && hasFiles
    }
}
