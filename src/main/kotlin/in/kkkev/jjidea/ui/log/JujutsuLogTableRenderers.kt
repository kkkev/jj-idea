package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.*
import kotlinx.datetime.Instant
import javax.swing.JTable

abstract class TextCellRenderer<T> : ColoredTableCellRenderer(), TextCanvas {
    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        (value as? T)?.let { render(it) }
    }

    abstract fun render(value: T)
}

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
class StatusCellRenderer : TextCellRenderer<LogEntry>() {
    override fun render(value: LogEntry) {
        when {
            value.hasConflict -> {
                icon = AllIcons.General.Warning
                append("!", SimpleTextAttributes.ERROR_ATTRIBUTES)
            }

            value.isEmpty -> {
                icon = AllIcons.General.BalloonInformation
                append("∅", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
}

/**
 * Renderer for the Change ID column.
 */
class ChangeIdCellRenderer : TextCellRenderer<ChangeId>() {
    override fun render(value: ChangeId) = append(value)
}

/**
 * Renderer for the Description column.
 * Phase 1: Just show description text
 * Phase 3: Add right-aligned refs with fancy icons
 */
class DescriptionCellRenderer : TextCellRenderer<LogEntry>() {
    override fun render(value: LogEntry) {
        appendSummary(value.description)

        // Phase 1: Show bookmarks as simple text
        append(value.bookmarks)

        // Set tooltip to full description
        toolTipText = value.description.display
    }
}

/**
 * Renderer for the Author column.
 */
class AuthorCellRenderer : TextCellRenderer<VcsUser>() {
    override fun render(value: VcsUser) {
        append(value.name)
    }
}

/**
 * Renderer for the Date column.
 * Shows formatted date/time using consistent formatter (Today/Yesterday/localized date).
 */
class DateCellRenderer : TextCellRenderer<Instant>() {
    override fun render(value: Instant) {
        append(value)

        // Tooltip shows full absolute time
        toolTipText = DateTimeFormatter.formatAbsolute(value)
    }
}

/**
 * Renderer for separate Change ID column.
 */
class SeparateChangeIdCellRenderer : TextCellRenderer<ChangeId>() {
    override fun render(value: ChangeId) = append(value)
}

/**
 * Renderer for separate Description column.
 */
class SeparateDescriptionCellRenderer : TextCellRenderer<Description>() {
    override fun render(value: Description) {
        append(value)

        // Set tooltip to full description with HTML formatting
        if (!value.empty) {
            toolTipText = formatDescriptionTooltip(value.actual)
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
 * Renderer for separate Decorations column (@ working copy marker and bookmarks).
 */
class SeparateDecorationsCellRenderer : TextCellRenderer<LogEntry>() {
    override fun render(value: LogEntry) {
        var hasContent = false

        // Show @ for working copy
        if (value.isWorkingCopy) {
            append("@", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JujutsuColors.WORKING_COPY))
            hasContent = true
        }

        // Show bookmarks
        value.bookmarks.forEachIndexed { index, bookmark ->
            if (hasContent || index > 0) append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(bookmark.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JujutsuColors.BOOKMARK))
            hasContent = true
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
