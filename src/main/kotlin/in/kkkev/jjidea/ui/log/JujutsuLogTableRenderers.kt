package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBValue
import com.intellij.util.ui.UIUtil
import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.DateTimeFormatter
import `in`.kkkev.jjidea.ui.JujutsuColors
import `in`.kkkev.jjidea.ui.TextCanvas
import `in`.kkkev.jjidea.ui.append
import kotlinx.datetime.Instant
import java.awt.*
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

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
 * Renderer for the Author column.
 */
class AuthorCellRenderer : TextCellRenderer<VcsUser>() {
    override fun render(value: VcsUser) {
        // Use bold for working copy row
        val attributes =
            if (isWorkingCopyRow) {
                SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            } else {
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
        append(value.name, attributes)
    }
}

/**
 * Renderer for the Committer column.
 */
class CommitterCellRenderer : TextCellRenderer<VcsUser>() {
    override fun render(value: VcsUser) {
        // Use bold for working copy row
        val attributes =
            if (isWorkingCopyRow) {
                SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            } else {
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
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
 * Renderer for separate ID column.
 */
class SeparateIdCellRenderer : TextCellRenderer<ChangeId>() {
    override fun render(value: ChangeId) {
        // TODO Should we embolden the working copy row? How would we do that and still use the append method?
        append(value)
    }
}

/**
 * Renderer for separate Description column with right-aligned decorations.
 * Uses custom painting to position description left-aligned and bookmarks/@ right-aligned.
 */
class SeparateDescriptionCellRenderer(private val table: JTable) : JPanel(), TableCellRenderer {
    companion object {
        // HiDPI-aware padding using JBValue for proper scaling
        private val HORIZONTAL_PADDING = JBValue.UIInteger("Jujutsu.Description.horizontalPadding", 4)
    }

    private var entry: LogEntry? = null
    private var isSelected = false
    private var isHovered = false

    init {
        isOpaque = true
    }

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        // Get entry from model
        val model = table.model as? JujutsuLogTableModel
        entry = model?.getEntry(row)

        this.isSelected = isSelected
        val mousePos = table.mousePosition
        this.isHovered = mousePos != null && table.rowAtPoint(mousePos) == row

        // Set background based on selection/hover state
        background =
            when {
                isSelected -> table.selectionBackground
                isHovered -> UIUtil.getListBackground(true, false)
                else -> table.background
            }

        // Set tooltip to full description with HTML formatting
        entry?.let { e ->
            if (!e.description.empty) {
                toolTipText = formatDescriptionTooltip(e.description.actual)
            } else {
                toolTipText = null
            }
        }

        return this
    }

    override fun paintComponent(g: Graphics) {
        val g2d = g as Graphics2D
        val entry = this.entry ?: return

        // Paint background explicitly
        g2d.color = background
        g2d.fillRect(0, 0, width, height)

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val centerY = height / 2
        val baseFontMetrics = g2d.fontMetrics
        val horizontalPadding = HORIZONTAL_PADDING.get()

        // Calculate decorations width
        val decorationsWidth = calculateDecorationsWidth(g2d, entry, horizontalPadding)

        // Calculate available width for description
        val maxDescriptionWidth = width - (horizontalPadding * 2) - decorationsWidth

        // Draw description
        val fontStyle = DescriptionRenderingStyle.getFontStyle(entry)
        g2d.font = baseFontMetrics.font.deriveFont(fontStyle)
        val fontMetrics = g2d.fontMetrics

        // Calculate width needed for "(empty)" indicator if applicable
        val emptyText = " (empty)"
        val emptyIndicatorWidth =
            if (entry.isEmpty) {
                val emptyStyle = DescriptionRenderingStyle.getEmptyIndicatorFontStyle(entry.isWorkingCopy)
                g2d.getFontMetrics(baseFontMetrics.font.deriveFont(emptyStyle)).stringWidth(emptyText)
            } else {
                0
            }

        // Reduce available width for description to reserve space for "(empty)"
        val availableWidthForDescription = maxDescriptionWidth - emptyIndicatorWidth

        // Set color using shared logic
        g2d.color = DescriptionRenderingStyle.getTextColor(
            entry.description,
            isSelected,
            table.selectionForeground,
            table.foreground
        )

        val text = entry.description.display

        // Truncate text if it doesn't fit
        val displayText = truncateText(text, fontMetrics, availableWidthForDescription)

        var x = horizontalPadding
        if (displayText.isNotEmpty()) {
            g2d.drawString(displayText, x, centerY + fontMetrics.ascent / 2)
            x += fontMetrics.stringWidth(displayText)
        }

        // Draw "(empty)" indicator if entry is empty
        if (entry.isEmpty) {
            val emptyStyle = DescriptionRenderingStyle.getEmptyIndicatorFontStyle(entry.isWorkingCopy)
            g2d.font = baseFontMetrics.font.deriveFont(emptyStyle)
            g2d.color = if (isSelected) table.selectionForeground else JBColor.GRAY
            g2d.drawString(emptyText, x, centerY + g2d.fontMetrics.ascent / 2)
        }

        // Reset font
        g2d.font = baseFontMetrics.font

        // Draw decorations on the right
        drawDecorations(g2d, entry, width - horizontalPadding, horizontalPadding)
    }

    private fun truncateText(text: String, fontMetrics: FontMetrics, availableWidth: Int): String {
        if (fontMetrics.stringWidth(text) <= availableWidth) return text

        // Binary search for the right length
        var truncated = text
        while (truncated.isNotEmpty() && fontMetrics.stringWidth(truncated + "...") > availableWidth) {
            truncated = truncated.dropLast(1)
        }
        return if (truncated.isEmpty()) "" else truncated + "..."
    }

    private fun calculateDecorationsWidth(g2d: Graphics2D, entry: LogEntry, horizontalPadding: Int): Int {
        if (entry.bookmarks.isEmpty() && !entry.isWorkingCopy) return 0

        val fontMetrics = g2d.fontMetrics
        val boldFont = fontMetrics.font.deriveFont(Font.BOLD)
        val boldFontMetrics = g2d.getFontMetrics(boldFont)
        var width = 0

        // Calculate bookmark widths using platform-style painter
        if (entry.bookmarks.isNotEmpty()) {
            val painter = JujutsuLabelPainter(this, compact = false)
            width += painter.calculateWidth(entry.bookmarks)
        }

        // Calculate @ symbol width
        if (entry.isWorkingCopy) {
            width += boldFontMetrics.stringWidth("@")
            width += horizontalPadding
        }

        return width
    }

    @Suppress("UNUSED_PARAMETER")
    private fun drawDecorations(g2d: Graphics2D, entry: LogEntry, rightX: Int, horizontalPadding: Int) {
        if (entry.bookmarks.isEmpty() && !entry.isWorkingCopy) return

        val centerY = height / 2
        val fontMetrics = g2d.fontMetrics
        val boldFont = fontMetrics.font.deriveFont(Font.BOLD)

        var x = rightX

        // Draw bookmarks using platform-style painter
        if (entry.bookmarks.isNotEmpty()) {
            val painter = JujutsuLabelPainter(this, compact = false)
            val background = when {
                isSelected -> table.selectionBackground
                isHovered -> UIUtil.getListBackground(true, false)
                else -> table.background
            }
            // Use grey text color (icon is orange, text is grey)
            val foreground = if (isSelected) table.selectionForeground else JBColor.GRAY

            x = painter.paintRightAligned(
                g2d,
                x,
                0,
                height,
                entry.bookmarks,
                background,
                foreground,
                isSelected
            )
        }

        // Draw @ symbol for working copy
        if (entry.isWorkingCopy) {
            g2d.font = boldFont
            g2d.color = if (isSelected) table.selectionForeground else JujutsuColors.WORKING_COPY
            val atWidth = fontMetrics.stringWidth("@")
            x -= atWidth
            g2d.drawString("@", x, centerY + fontMetrics.ascent / 2)
        }

        // Reset font
        g2d.font = fontMetrics.font
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
    val descriptionRenderer = SeparateDescriptionCellRenderer(this)
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
