package `in`.kkkev.jjidea.vcs.log

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.vcs.log.VcsRef
import com.intellij.vcs.log.ui.table.GraphTableModel
import com.intellij.vcs.log.ui.table.VcsLogGraphTable
import com.intellij.vcs.log.ui.table.VcsLogTableIndex
import com.intellij.vcs.log.ui.table.column.VcsLogCustomColumn
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeStatus
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.JujutsuCommitMetadataBase
import `in`.kkkev.jjidea.jj.LogEntry
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

interface LogEntryCustomColumn<T> : VcsLogCustomColumn<T> {
    override val isDynamic get() = true

    override fun getValue(model: GraphTableModel, row: VcsLogTableIndex): T? {
        val metadata = model.getCommitMetadata(row) as? JujutsuCommitMetadataBase
        return metadata?.entry?.let(this::getValue)
    }

    fun getValue(logEntry: LogEntry): T
}

/**
 * Description column with refs/bookmarks rendered using platform label painter.
 * Data class to hold description + refs for rendering.
 */
data class DescriptionWithRefs(val description: Description, val refs: List<VcsRef>)

class JujutsuDescriptionColumn : VcsLogCustomColumn<DescriptionWithRefs> {
    override val id = "Jujutsu.Description"
    override val localizedName = JujutsuBundle.message("column.description")
    override val isDynamic = true

    override fun getValue(model: GraphTableModel, row: VcsLogTableIndex): DescriptionWithRefs? {
        val metadata = model.getCommitMetadata(row) as? JujutsuCommitMetadataBase ?: return null
        val refs = model.getRefsAtRow(row)
        return DescriptionWithRefs(metadata.entry.description, refs)
    }

    override fun createTableCellRenderer(table: VcsLogGraphTable): TableCellRenderer = JujutsuDescriptionRenderer

    override fun getStubValue(model: GraphTableModel) = DescriptionWithRefs(Description.EMPTY, emptyList())
}

/**
 * Renderer for description column that shows refs/bookmarks with colored labels.
 */
private object JujutsuDescriptionRenderer : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        val data = value as? DescriptionWithRefs ?: return
        val description = data.description

        // Append description with formatting for empty
        val style = if (description.empty) {
            SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
        } else {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        append(description.summary, style)

        // Append refs/bookmarks on the right
        if (data.refs.isNotEmpty()) {
            append("  ")
            data.refs.forEachIndexed { index, ref ->
                // Use colored bold text for ref names
                val color = ref.type.backgroundColor
                append(
                    ref.name,
                    SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_SMALLER,
                        color
                    )
                )
                if (index < data.refs.size - 1) {
                    append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        // Set tooltip to full description
        if (!description.empty) {
            toolTipText = description.actual
        }
    }
}

/**
 * Custom Status column for Jujutsu VCS Log - shows conflict/empty indicators
 */
class JujutsuStatusColumn : LogEntryCustomColumn<ChangeStatus> {
    override val id = "Jujutsu.Status"
    override val localizedName = JujutsuBundle.message("column.status")

    override fun getValue(logEntry: LogEntry) = logEntry

    override fun createTableCellRenderer(table: VcsLogGraphTable) =
        object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ) {
                if (value !is ChangeStatus) return

                // Show conflict icon
                if (value.hasConflict) {
                    icon = AllIcons.General.Warning
                    append(" ")
                }
                // Show empty icon
                if (value.isEmpty) {
                    icon = if (value.hasConflict) {
                        // Both indicators - use warning icon, add text
                        append(JujutsuBundle.message("status.empty") + " ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        AllIcons.General.Warning
                    } else {
                        AllIcons.General.BalloonInformation
                    }
                }
            }
        }

    override fun getStubValue(model: GraphTableModel) = ChangeStatus.Default

    override fun isEnabledByDefault() = true

    override fun isAvailable(project: Project, roots: Collection<VirtualFile>) = true
}

/**
 * Custom Change ID column for Jujutsu VCS Log
 */
class JujutsuChangeIdColumn : LogEntryCustomColumn<ChangeId> {
    override val id = "Jujutsu.ChangeId"
    override val localizedName = JujutsuBundle.message("column.changeid")

    override fun getValue(logEntry: LogEntry) = logEntry.changeId

    override fun createTableCellRenderer(table: VcsLogGraphTable) =
        object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ) {
                if (value is ChangeId) {
                    // Show JJ's dynamic short prefix in bold
                    append(value.short, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    // Show remainder up to display limit in gray/small
                    if (value.displayRemainder.isNotEmpty()) {
                        append(value.displayRemainder, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
            }
        }

    override fun getStubValue(model: GraphTableModel) = ChangeId("q")

    override fun isEnabledByDefault(): Boolean = true

    override fun isAvailable(project: Project, roots: Collection<VirtualFile>): Boolean = true
}

/**
 * Custom Bookmarks column for Jujutsu VCS Log
 */
class JujutsuBookmarksColumn : VcsLogCustomColumn<String> {
    override val id: String = "Jujutsu.Bookmarks"

    override val localizedName: String = JujutsuBundle.message("column.bookmarks")

    override val isDynamic: Boolean = true

    override fun getValue(model: GraphTableModel, row: Int): String? {
        val metadata = model.getCommitMetadata(row) as? JujutsuCommitMetadataBase ?: return null
        val bookmarks = metadata.entry.bookmarks.joinToString(", ") { it.name }
        return if (bookmarks.isEmpty()) null else bookmarks
    }

    override fun createTableCellRenderer(table: VcsLogGraphTable): TableCellRenderer =
        object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ) {
                if (value is String && value.isNotEmpty()) {
                    append(value, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

    override fun getStubValue(model: GraphTableModel) = ""

    override fun isEnabledByDefault(): Boolean = true

    override fun isAvailable(project: Project, roots: Collection<VirtualFile>): Boolean = true
}

/**
 * Custom Committer column for Jujutsu VCS Log (hidden by default)
 */
class JujutsuCommitterColumn : VcsLogCustomColumn<String> {
    override val id: String = "Jujutsu.Committer"

    override val localizedName: String = JujutsuBundle.message("column.committer")

    override val isDynamic: Boolean = true

    override fun getValue(model: GraphTableModel, row: Int): String? {
        val metadata = model.getCommitMetadata(row) as? JujutsuCommitMetadataBase ?: return null
        return metadata.entry.committer?.name ?: metadata.entry.author?.name
    }

    override fun createTableCellRenderer(table: VcsLogGraphTable): TableCellRenderer =
        object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ) {
                if (value is String) {
                    append(value, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

    override fun getStubValue(model: GraphTableModel): String = JujutsuBundle.message("log.stub.author")

    override fun isEnabledByDefault(): Boolean = false // Hidden by default

    override fun isAvailable(project: Project, roots: Collection<VirtualFile>): Boolean = true
}
