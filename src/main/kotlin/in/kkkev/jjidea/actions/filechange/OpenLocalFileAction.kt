package `in`.kkkev.jjidea.actions.filechange

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.vcs.filePath

/**
 * Opens the local working copy version of the selected historical file.
 * Only visible in historical contexts (not working copy).
 */
class OpenLocalFileAction : HistoricalVersionAction("action.open.local.file", AllIcons.Actions.EditSource) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val localFiles = e.changes.mapNotNull { it.after?.filePath?.virtualFile }
            .ifEmpty { listOfNotNull(e.file?.filePath?.virtualFile) }
        localFiles.forEach { OpenFileDescriptor(project, it).navigate(true) }
    }
}
