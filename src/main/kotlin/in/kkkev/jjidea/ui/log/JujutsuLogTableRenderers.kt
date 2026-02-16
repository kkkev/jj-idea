package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.components.*
import kotlinx.datetime.Instant
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

abstract class TextCellRenderer<T> : ColoredTableCellRenderer() {
    val canvas = object : StyledTextCanvas() {
        override fun append(text: String) {
            this@TextCellRenderer.append(text, style)
        }
    }

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

        @Suppress("UNCHECKED_CAST")
        (value as? T)?.let { render(it) }
    }

    abstract fun render(value: T)
}

/*
 * Cell renderers for JujutsuLogTable columns.
 * Phase 1: Simple text-based rendering
 * Phase 2: Add graph rendering
 * Phase 3: Add fancy icons and styling
 *
 * Note: Graph renderer is now in JujutsuGraphCellRenderer.kt
 * It's set dynamically when graph data is loaded
 */

/**
 * Renderer for separate Status column (conflict/empty indicators).
 */
class SeparateStatusCellRenderer : TextCellRenderer<LogEntry>() {
    companion object {
        private const val CONFLICT_TOOLTIP = "Conflict - This change has unresolved merge conflicts"
        private const val EMPTY_TOOLTIP = "Empty - This change has no file modifications"
        private const val IMMUTABLE_TOOLTIP = "Immutable - This change cannot be modified (protected)"
    }

    override fun render(value: LogEntry) {
        val hasMultipleIndicators = listOf(value.hasConflict, value.isEmpty, value.immutable).count { it } > 1

        // Use bold attributes for working copy row
        val textAttributes = if (value.isWorkingCopy) {
            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor)
        } else {
            SimpleTextAttributes.GRAYED_ATTRIBUTES
        }

        // Build tooltip parts
        val tooltipParts = mutableListOf<String>()

        // Show conflict icon/text
        if (value.hasConflict) {
            tooltipParts.add(CONFLICT_TOOLTIP)
            if (hasMultipleIndicators) {
                append("Conflict", textAttributes)
                append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            } else {
                icon = AllIcons.General.Warning
            }
        }

        // Show empty indicator - always use text for clarity
        if (value.isEmpty) {
            tooltipParts.add(EMPTY_TOOLTIP)
            append("Empty", textAttributes)
            if (value.immutable) {
                append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }

        // Show immutable icon/text
        if (value.immutable) {
            tooltipParts.add(IMMUTABLE_TOOLTIP)
            if (hasMultipleIndicators) {
                append("Immutable", textAttributes)
            } else {
                icon = AllIcons.Nodes.Locked
            }
        }

        // Set tooltip if any indicators present, clear if none
        toolTipText = if (tooltipParts.isNotEmpty()) {
            "<html>${tooltipParts.joinToString("<br>")}</html>"
        } else {
            null
        }
    }
}

/**
 * Renderer for the Author and Committer columns.
 */
class UserCellRenderer : TextCellRenderer<VcsUser>() {
    override fun render(value: VcsUser) {
        canvas.styled(if (isWorkingCopyRow) Font.BOLD else 0) { append(value) }
        toolTipText = value.email
    }
}

/**
 * Renderer for the Date column.
 * Shows formatted date/time using consistent formatter (Today/Yesterday/localized date).
 */
class DateCellRenderer : TextCellRenderer<Instant>() {
    override fun render(value: Instant) {
        canvas.styled(if (isWorkingCopyRow) Font.BOLD else 0) { append(value) }

        // Tooltip shows full absolute time
        toolTipText = DateTimeFormatter.formatAbsolute(value)
    }
}

/**
 * Renderer for separate ID column.
 */
class SeparateIdCellRenderer : TextCellRenderer<ChangeId>() {
    override fun render(value: ChangeId) = canvas.styled(if (isWorkingCopyRow) Font.BOLD else 0) { append(value) }
}

/**
 * Build a [FragmentRecordingCanvas] with the standard row styling (bold for working copy,
 * foreground color for selection state) applied around [builder].
 */
fun entryCanvas(entry: LogEntry, fg: Color, builder: TextCanvas.() -> Unit) = FragmentRecordingCanvas().apply {
    styled(if (entry.isWorkingCopy) Font.BOLD else 0) {
        colored(fg, builder)
    }
}

/** Append status indicators: immutable/public icon and conflict warning. */
fun TextCanvas.appendStatusIndicators(entry: LogEntry) {
    append(icon(if (entry.immutable) AllIcons.Nodes::Private else AllIcons.Nodes::Public))
    if (entry.hasConflict) {
        append(icon(JujutsuIcons::Conflict, JujutsuColors.CONFLICT))
        append(" ")
    }
}

/** Append right-side decorations: bookmarks and working copy indicator. */
fun TextCanvas.appendDecorations(entry: LogEntry) {
    appendBookmarks(entry)
    if (entry.isWorkingCopy) {
        append(" ")
        colored(JujutsuColors.WORKING_COPY) { bold { append("@") } }
    }
}

class SeparateDescriptionCellRenderer() : TableCellRenderer {
    private val panel = TruncatingLeftRightLayout()

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val model = table.model as? JujutsuLogTableModel
        val entry = model?.getEntry(row) ?: return panel

        val mousePos = table.mousePosition
        val isHovered = mousePos != null && table.rowAtPoint(mousePos) == row
        val bg = when {
            isSelected -> table.selectionBackground
            isHovered -> UIUtil.getListBackground(true, false)
            else -> table.background
        }
        val fg = if (isSelected) table.selectionForeground else table.foreground

        panel.configure(
            leftCanvas = entryCanvas(entry, fg) { appendDescriptionAndEmptyIndicator(entry) },
            rightCanvas = entryCanvas(entry, fg) { appendDecorations(entry) },
            cellWidth = table.columnModel.getColumn(column).width,
            background = bg
        )

        panel.toolTipText = htmlString { append(entry.description) }
        return panel
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

            // icon = JujutsuIcons.Bookmark
            icon = AllIcons.Nodes.Bookmark

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
private val DEFAULT_COLUMN_WIDTHS =
    mapOf(
        JujutsuLogTableModel.COLUMN_ROOT_GUTTER to 8, // Will be scaled with JBUI
        JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION to 600,
        JujutsuLogTableModel.COLUMN_STATUS to 50,
        JujutsuLogTableModel.COLUMN_ID to 90,
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
    val rootGutterRenderer = JujutsuRootGutterRenderer()
    val statusRenderer = SeparateStatusCellRenderer()
    val changeIdRenderer = SeparateIdCellRenderer()
    val descriptionRenderer = SeparateDescriptionCellRenderer()
    val decorationsRenderer = SeparateDecorationsCellRenderer()
    val authorRenderer = UserCellRenderer()
    val committerRenderer = UserCellRenderer()
    val dateRenderer = DateCellRenderer()

    // Iterate through actual columns in the column model
    for (i in 0 until columnModel.columnCount) {
        val column = columnModel.getColumn(i)
        val modelIndex = column.modelIndex

        // Set renderer and width based on model index
        val defaultWidth = DEFAULT_COLUMN_WIDTHS[modelIndex] ?: 100

        when (modelIndex) {
            JujutsuLogTableModel.COLUMN_ROOT_GUTTER -> {
                column.cellRenderer = rootGutterRenderer
                // Use a small fixed width for the collapsed gutter
                val gutterWidth = JBUI.scale(8)
                column.preferredWidth = gutterWidth
                column.width = gutterWidth
                column.minWidth = gutterWidth
                column.maxWidth = gutterWidth
            }

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

            JujutsuLogTableModel.COLUMN_ID -> {
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
