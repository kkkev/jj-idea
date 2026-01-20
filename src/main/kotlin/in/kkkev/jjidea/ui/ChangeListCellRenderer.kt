package `in`.kkkev.jjidea.ui

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.io.File
import javax.swing.JList

/**
 * Custom cell renderer for the changes list
 * Shows file icons and colors based on status (like Git commit view)
 */
class ChangeListCellRenderer(private val project: Project) : ColoredListCellRenderer<Change>() {
    override fun customizeCellRenderer(
        list: JList<out Change>,
        value: Change?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ) {
        if (value == null) return

        val filePath = value.afterRevision?.file?.path ?: value.beforeRevision?.file?.path ?: return
        val fileName = File(filePath).name

        // Get file icon based on file type
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        icon = fileType.icon

        // Get file status and corresponding color
        val fileStatus = getFileStatus(value)
        val attributes = getTextAttributes(fileStatus)

        // Append filename with status color
        append(fileName, attributes)

        // Optionally show partial path
        val relativePath = File(filePath).parent
        if (relativePath != null) {
            append("  $relativePath", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private fun getFileStatus(change: Change): FileStatus = when (change.type) {
        Change.Type.MODIFICATION -> FileStatus.MODIFIED
        Change.Type.NEW -> FileStatus.ADDED
        Change.Type.DELETED -> FileStatus.DELETED
        Change.Type.MOVED -> FileStatus.MODIFIED
        else -> FileStatus.NOT_CHANGED
    }

    private fun getTextAttributes(status: FileStatus): SimpleTextAttributes =
        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.color)
}
