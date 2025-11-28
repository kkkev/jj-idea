package `in`.kkkev.jjidea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.io.File
import javax.swing.JTree

/**
 * Custom cell renderer for the changes tree
 * Shows file icons, colors based on status, and file counts for directories
 */
class JujutsuChangesTreeCellRenderer(private val project: Project) : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        when (value) {
            is JujutsuChangesTreeModel.DirectoryNode -> {
                icon = if (expanded) AllIcons.Nodes.Folder else AllIcons.Nodes.Folder
                append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }

            is JujutsuChangesTreeModel.ChangeNode -> {
                val change = value.change
                val filePath = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: return
                val fileName = File(filePath).name

                // Get file icon based on file type
                val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
                icon = fileType.icon

                // Get file status and corresponding color
                val fileStatus = getFileStatus(change)
                val attributes = getTextAttributes(fileStatus)

                // Append filename with status color
                append(fileName, attributes)
            }

            else -> {
                // Root node or other
                icon = AllIcons.Vcs.Branch
                append(value.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
        }
    }

    private fun getFileStatus(change: Change) = when (change.type) {
        Change.Type.MODIFICATION -> FileStatus.MODIFIED
        Change.Type.NEW -> FileStatus.ADDED
        Change.Type.DELETED -> FileStatus.DELETED
        Change.Type.MOVED -> FileStatus.MODIFIED
    }

    private fun getTextAttributes(status: FileStatus): SimpleTextAttributes =
        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.color)
}
