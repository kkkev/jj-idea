package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import kotlinx.datetime.Clock
import java.awt.Component
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.JTable

/**
 * Cell renderers for JujutsuLogTable columns.
 * Phase 1: Simple text-based rendering
 * Phase 2: Add graph rendering
 * Phase 3: Add fancy icons and styling
 */

// Graph renderer is now in JujutsuGraphCellRenderer.kt
// It's set dynamically when graph data is loaded

/**
 * Renderer for the Status column (conflict/empty indicators).
 */
class StatusCellRenderer : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        val entry = value as? LogEntry ?: return

        when {
            entry.hasConflict -> {
                icon = AllIcons.General.Warning
                append("!", SimpleTextAttributes.ERROR_ATTRIBUTES)
            }
            entry.isEmpty -> {
                icon = AllIcons.General.BalloonInformation
                append("∅", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
}

/**
 * Renderer for the Change ID column.
 */
class ChangeIdCellRenderer : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        val changeId = value as? ChangeId ?: return

        // Show JJ's dynamic short prefix in bold
        append(changeId.short, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)

        // Show remainder up to display limit in gray/small
        if (changeId.displayRemainder.isNotEmpty()) {
            append(changeId.displayRemainder, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}

/**
 * Renderer for the Description column.
 * Phase 1: Just show description text
 * Phase 3: Add right-aligned refs with fancy icons
 */
class DescriptionCellRenderer : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        val entry = value as? LogEntry ?: return

        // Show description (italic if empty)
        val attributes = if (entry.description.empty) {
            SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
        } else {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        }

        append(entry.description.summary, attributes)

        // Phase 1: Show bookmarks as simple text
        if (entry.bookmarks.isNotEmpty()) {
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            entry.bookmarks.forEach { bookmark ->
                append(bookmark.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }

        // Set tooltip to full description
        if (!entry.description.empty) {
            toolTipText = entry.description.actual
        }
    }
}

/**
 * Renderer for the Author column.
 */
class AuthorCellRenderer : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        val author = value as? String ?: return
        append(author, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }
}

/**
 * Renderer for the Date column.
 * Shows relative time (e.g., "2 hours ago") with absolute time in tooltip.
 */
class DateCellRenderer : ColoredTableCellRenderer() {
    private val absoluteDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        val timestamp = value as? kotlinx.datetime.Instant ?: return
        val date = Date(timestamp.toEpochMilliseconds())

        // Show relative time
        append(getRelativeTime(timestamp), SimpleTextAttributes.GRAYED_ATTRIBUTES)

        // Tooltip shows absolute time
        toolTipText = absoluteDateFormat.format(date)
    }

    private fun getRelativeTime(timestamp: kotlinx.datetime.Instant): String {
        val now = Clock.System.now()
        val duration = now - timestamp

        return when {
            duration.inWholeSeconds < 60 -> "just now"
            duration.inWholeMinutes < 60 -> "${duration.inWholeMinutes}m ago"
            duration.inWholeHours < 24 -> "${duration.inWholeHours}h ago"
            duration.inWholeDays < 7 -> "${duration.inWholeDays}d ago"
            duration.inWholeDays < 30 -> "${duration.inWholeDays / 7}w ago"
            duration.inWholeDays < 365 -> "${duration.inWholeDays / 30}mo ago"
            else -> "${duration.inWholeDays / 365}y ago"
        }
    }
}

/**
 * Renderer for separate Change ID column.
 */
class SeparateChangeIdCellRenderer : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        val changeId = value as? ChangeId ?: return

        // Show JJ's dynamic short prefix in bold
        append(changeId.short, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)

        // Show remainder up to display limit in gray/small
        if (changeId.displayRemainder.isNotEmpty()) {
            append(changeId.displayRemainder, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}

/**
 * Renderer for separate Description column.
 */
class SeparateDescriptionCellRenderer : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        val description = value as? Description ?: return

        // Show description (italic if empty)
        val attributes = if (description.empty) {
            SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
        } else {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        }

        append(description.summary, attributes)

        // Set tooltip to full description with HTML formatting
        if (!description.empty) {
            toolTipText = formatDescriptionTooltip(description.actual)
        }
    }

    private fun formatDescriptionTooltip(description: String): String {
        // Convert to HTML and replace newlines with <br>
        val htmlEscaped = description
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")

        return "<html>$htmlEscaped</html>"
    }
}

/**
 * Renderer for separate Decorations column (bookmarks/tags).
 */
class SeparateDecorationsCellRenderer : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        val bookmarks = value as? List<*> ?: return

        bookmarks.filterIsInstance<Bookmark>().forEachIndexed { index, bookmark ->
            if (index > 0) append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(bookmark.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        }
    }
}

/**
 * Install all custom renderers on the given table.
 * Note: Combined graph+description renderer is installed separately when graph data is loaded.
 * Only installs renderers for columns that are actually present in the column model.
 */
fun JujutsuLogTable.installRenderers() {
    val changeIdRenderer = SeparateChangeIdCellRenderer()
    val descriptionRenderer = SeparateDescriptionCellRenderer()
    val decorationsRenderer = SeparateDecorationsCellRenderer()
    val authorRenderer = AuthorCellRenderer()
    val dateRenderer = DateCellRenderer()

    // Iterate through actual columns in the column model
    for (i in 0 until columnModel.columnCount) {
        val column = columnModel.getColumn(i)
        val modelIndex = column.modelIndex

        // Set renderer based on model index
        when (modelIndex) {
            JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION -> {
                // Will be set when graph data is loaded via updateGraph()
                column.preferredWidth = 600
            }
            JujutsuLogTableModel.COLUMN_CHANGE_ID -> {
                column.cellRenderer = changeIdRenderer
                column.preferredWidth = 100
            }
            JujutsuLogTableModel.COLUMN_DESCRIPTION -> {
                column.cellRenderer = descriptionRenderer
                column.preferredWidth = 300
            }
            JujutsuLogTableModel.COLUMN_DECORATIONS -> {
                column.cellRenderer = decorationsRenderer
                column.preferredWidth = 150
            }
            JujutsuLogTableModel.COLUMN_AUTHOR -> {
                column.cellRenderer = authorRenderer
                column.preferredWidth = 120
            }
            JujutsuLogTableModel.COLUMN_DATE -> {
                column.cellRenderer = dateRenderer
                column.preferredWidth = 80
                column.maxWidth = 120
            }
        }
    }
}
