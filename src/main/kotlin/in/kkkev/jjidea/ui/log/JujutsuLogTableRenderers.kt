package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.DateTimeFormatter
import `in`.kkkev.jjidea.ui.JujutsuColors
import `in`.kkkev.jjidea.ui.TextCanvas
import `in`.kkkev.jjidea.ui.append
import kotlinx.datetime.Instant
import javax.swing.JTable

abstract class TextCellRenderer<T> : ColoredTableCellRenderer(), TextCanvas {
    protected var isWorkingCopyRow = false

    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        // Check if this row is the working copy
        val model = table.model as? JujutsuLogTableModel
        isWorkingCopyRow = model?.getEntry(row)?.isWorkingCopy ?: false

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
 * Renderer for separate Status column (conflict/empty indicators).
 * Matches the logic from JujutsuStatusColumn in VcsLogCustomColumns.
 */
class SeparateStatusCellRenderer : TextCellRenderer<LogEntry>() {
    override fun render(value: LogEntry) {
        val hasMultipleIndicators = listOf(value.hasConflict, value.isEmpty, value.immutable).count { it } > 1

        // Use bold attributes for working copy row
        val textAttributes = if (value.isWorkingCopy) {
            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor)
        } else {
            SimpleTextAttributes.GRAYED_ATTRIBUTES
        }

        // Show conflict icon/text
        if (value.hasConflict) {
            if (hasMultipleIndicators) {
                append("Conflict", textAttributes)
                append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            } else {
                icon = AllIcons.General.Warning
            }
        }

        // Show empty indicator - always use text for clarity
        if (value.isEmpty) {
            append("Empty", textAttributes)
            if (value.immutable) {
                append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }

        // Show immutable icon/text
        if (value.immutable) {
            if (hasMultipleIndicators) {
                append("Immutable", textAttributes)
            } else {
                icon = AllIcons.Nodes.Locked
            }
        }
    }
}

/**
 * Renderer for the Change ID column.
 */
class ChangeIdCellRenderer : TextCellRenderer<ChangeId>() {
    override fun render(value: ChangeId) {
        // Use bold for working copy row
        if (isWorkingCopyRow) {
            append(value.short, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (value.displayRemainder.isNotEmpty()) {
                append(value.displayRemainder, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        } else {
            append(value)
        }
    }
}

/**
 * Renderer for the Description column.
 * Phase 1: Just show description text
 * Phase 3: Add right-aligned refs with fancy icons
 */
class DescriptionCellRenderer : TextCellRenderer<LogEntry>() {
    override fun render(value: LogEntry) {
        // Use shared style logic
        val descAttributes = DescriptionRenderingStyle.getTextAttributes(value)

        // Append description summary
        append(value.description.summary, descAttributes)

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
        // Use bold for working copy row
        val attributes =
            if (isWorkingCopyRow) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
        append(value.name, attributes)
    }
}

/**
 * Renderer for the Committer column.
 * Matches the logic from JujutsuCommitterColumn in VcsLogCustomColumns.
 */
class CommitterCellRenderer : TextCellRenderer<VcsUser>() {
    override fun render(value: VcsUser) {
        // Use bold for working copy row
        val attributes =
            if (isWorkingCopyRow) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
        append(value.name, attributes)
    }
}

/**
 * Renderer for the Date column.
 * Shows formatted date/time using consistent formatter (Today/Yesterday/localized date).
 */
class DateCellRenderer : TextCellRenderer<Instant>() {
    override fun render(value: Instant) {
        // Use bold for working copy row
        if (isWorkingCopyRow) {
            val dateStr = DateTimeFormatter.formatRelative(value)
            append(dateStr, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        } else {
            append(value)
        }

        // Tooltip shows full absolute time
        toolTipText = DateTimeFormatter.formatAbsolute(value)
    }
}

/**
 * Renderer for separate Change ID column.
 */
class SeparateChangeIdCellRenderer : TextCellRenderer<ChangeId>() {
    override fun render(value: ChangeId) {
        // Use bold for working copy row
        if (isWorkingCopyRow) {
            append(value.short, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (value.displayRemainder.isNotEmpty()) {
                append(value.displayRemainder, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        } else {
            append(value)
        }
    }
}

/**
 * Renderer for separate Description column.
 */
class SeparateDescriptionCellRenderer : TextCellRenderer<Description>() {
    override fun render(value: Description) {
        // Use shared style logic
        val attributes = DescriptionRenderingStyle.getTextAttributes(value, isWorkingCopyRow)

        // Render description text
        append(value.display, attributes)

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
 * Uses platform-style bookmark icon for native appearance.
 *
 * Note: ColoredTableCellRenderer only supports one icon per cell, so we show a single
 * bookmark icon at the start if there are any bookmarks. Full platform-style rendering
 * with icon-per-bookmark will be implemented in jj-idea-srz (right-aligned refs).
 */
class SeparateDecorationsCellRenderer : TextCellRenderer<LogEntry>() {
    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        // Clear tooltip before rendering to prevent flashing
        toolTipText = null
        super.customizeCellRenderer(table, value, selected, hasFocus, row, column)
    }

    override fun render(value: LogEntry) {
        var hasContent = false

        // Show @ for working copy
        if (value.isWorkingCopy) {
            append("@", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JujutsuColors.WORKING_COPY))
            hasContent = true
        }

        // Show bookmarks with platform-style icon at the start
        if (value.bookmarks.isNotEmpty()) {
            if (hasContent) append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // Set platform-style bookmark icon (appears once at start of bookmarks)
            // Use font metrics height for proper scaling
            val fontMetrics = getFontMetrics(font)
            val iconHeight = fontMetrics.height
            icon = JujutsuBookmarkIcon(iconHeight)

            // Render bookmark names with smaller, grayed text (grey text, not orange)
            value.bookmarks.forEachIndexed { index, bookmark ->
                if (index > 0) append(", ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                append(bookmark.name, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }
}

/**
 * Default column widths (sensible defaults that can be overridden by user preferences).
 */
private val DEFAULT_COLUMN_WIDTHS = mapOf(
    JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION to 600,
    JujutsuLogTableModel.COLUMN_STATUS to 50,
    JujutsuLogTableModel.COLUMN_CHANGE_ID to 90,
    JujutsuLogTableModel.COLUMN_DESCRIPTION to 500,
    JujutsuLogTableModel.COLUMN_DECORATIONS to 120,
    JujutsuLogTableModel.COLUMN_AUTHOR to 100,
    JujutsuLogTableModel.COLUMN_COMMITTER to 100,
    JujutsuLogTableModel.COLUMN_DATE to 120
)

/**
 * Install all custom renderers on the given table.
 * Note: Combined graph+description renderer is installed separately when graph data is loaded.
 * Only installs renderers for columns that are actually present in the column model.
 */
fun JujutsuLogTable.installRenderers() {
    val statusRenderer = SeparateStatusCellRenderer()
    val changeIdRenderer = SeparateChangeIdCellRenderer()
    val descriptionRenderer = SeparateDescriptionCellRenderer()
    val decorationsRenderer = SeparateDecorationsCellRenderer()
    val authorRenderer = AuthorCellRenderer()
    val committerRenderer = CommitterCellRenderer()
    val dateRenderer = DateCellRenderer()

    // Iterate through actual columns in the column model
    for (i in 0 until columnModel.columnCount) {
        val column = columnModel.getColumn(i)
        val modelIndex = column.modelIndex

        // Set renderer and width based on model index
        val defaultWidth = DEFAULT_COLUMN_WIDTHS[modelIndex] ?: 100

        when (modelIndex) {
            JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION -> {
                // Will be set when graph data is loaded via updateGraph()
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.maxWidth = Int.MAX_VALUE
            }

            JujutsuLogTableModel.COLUMN_STATUS -> {
                column.cellRenderer = statusRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = 40
            }

            JujutsuLogTableModel.COLUMN_CHANGE_ID -> {
                column.cellRenderer = changeIdRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = 70
            }

            JujutsuLogTableModel.COLUMN_DESCRIPTION -> {
                column.cellRenderer = descriptionRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = 100
                column.maxWidth = Int.MAX_VALUE
            }

            JujutsuLogTableModel.COLUMN_DECORATIONS -> {
                column.cellRenderer = decorationsRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = 80
            }

            JujutsuLogTableModel.COLUMN_AUTHOR -> {
                column.cellRenderer = authorRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = 80
            }

            JujutsuLogTableModel.COLUMN_COMMITTER -> {
                column.cellRenderer = committerRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = 80
            }

            JujutsuLogTableModel.COLUMN_DATE -> {
                column.cellRenderer = dateRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = 70
            }
        }
    }
}
