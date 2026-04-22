package `in`.kkkev.jjidea.actions.filechange

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.files
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.cacheContents

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

        runInBackground {
            e.files.takeUnless { it.isEmpty() }?.let { files ->
                files.forEach { it.cacheContents() }
                runLater {
                    val fileEditorManager = FileEditorManager.getInstance(project)
                    files.forEach { file ->
                        fileEditorManager.openFile(file, true)
                        OpenFileDescriptor(project, file).navigate(true)
                    }
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val hasFiles = e.filePaths.isNotEmpty() ||
            e.changes.any { it.after != null }
        e.presentation.isEnabled = e.project != null && hasFiles
    }
}
