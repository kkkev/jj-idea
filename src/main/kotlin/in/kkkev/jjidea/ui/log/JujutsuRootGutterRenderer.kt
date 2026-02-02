package `in`.kkkev.jjidea.ui.log

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent.getTextBaseLine
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.LogEntry
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Renderer for the root gutter column.
 *
 * Visual design inspired by native VCS log:
 * - Collapsed: Thin colored bar with small gaps between groups of same-repo commits
 * - Expanded: Colored background with repo name
 *
 * The bar has slightly rounded ends and shows gaps when the next/previous row
 * is from a different repository.
 */
class JujutsuRootGutterRenderer : TableCellRenderer {
    companion object {
        private val BAR_WIDTH = JBUI.scale(4)
        private val GAP_SIZE = JBUI.scale(2)
        private val CORNER_RADIUS = JBUI.scale(2)
    }

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val logTable = table as? JujutsuLogTable
        val isExpanded = logTable?.isRootGutterExpanded ?: false
        // Get the actual column width from the column model to ensure proper clipping
        val columnWidth = table.columnModel.getColumn(column).width
        return GutterPanel(table, row, isSelected, isExpanded, columnWidth)
    }

    private inner class GutterPanel(
        private val table: JTable,
        private val row: Int,
        private val isSelected: Boolean,
        private val isExpanded: Boolean,
        private val columnWidth: Int
    ) : JPanel() {
        private var entry: LogEntry? = null

        init {
            // Set opaque to false - we'll paint our own background explicitly
            isOpaque = false

            // Get entry for this row
            val model = table.model as? JujutsuLogTableModel
            entry = model?.getEntry(row)

            // Set tooltip
            toolTipText = JujutsuBundle.message("log.filter.root") + ": " +
                (entry?.repo?.displayName ?: "") +
                if (!isExpanded) "\n(Click to expand)" else "\n(Click to collapse)"
        }

        override fun paintComponent(g: Graphics) {
            // Don't call super - we handle all painting ourselves
            // Note: isOpaque = false, so table paints background behind us

            val g2d = g.create() as Graphics2D
            try {
                // Use the actual column width for painting bounds - ONLY paint within this
                val paintWidth = minOf(width, columnWidth)

                // ONLY paint within the column bounds - leave the rest transparent
                // so the table's background shows through for any spill area
                g2d.clip = Rectangle(0, 0, paintWidth, height)

                // Paint neutral background ONLY within column bounds
                g2d.color = table.background
                g2d.fillRect(0, 0, paintWidth, height)

                val entry = this.entry ?: return
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val repoColor = RepositoryColors.getColor(entry.repo)

                if (isExpanded) {
                    paintExpanded(g2d, entry, repoColor, paintWidth)
                } else {
                    paintCollapsed(g2d, entry, repoColor, paintWidth)
                }
            } finally {
                g2d.dispose()
            }
        }

        private fun paintExpanded(g2d: Graphics2D, entry: LogEntry, repoColor: JBColor, clipWidth: Int) {
            val model = table.model as? JujutsuLogTableModel ?: return

            // Check if previous/next rows are from the same repo
            val prevEntry = if (row > 0) model.getEntry(row - 1) else null
            val nextEntry = model.getEntry(row + 1)

            val sameAsPrev = prevEntry?.repo == entry.repo
            val sameAsNext = nextEntry?.repo == entry.repo

            // Calculate rectangle position and size with gaps
            var rectY = 0
            var rectHeight = height

            // Add gaps at top/bottom if different repo
            if (!sameAsPrev) {
                rectY = GAP_SIZE
                rectHeight -= GAP_SIZE
            }
            if (!sameAsNext) {
                rectHeight -= GAP_SIZE
            }

            // Determine which corners to round
            val roundTop = !sameAsPrev
            val roundBottom = !sameAsNext

            // Draw colored background with rounded corners at group boundaries
            // Use clipWidth instead of width to stay within column bounds
            g2d.color = RepositoryColors.getBackgroundColor(repoColor)
            if (roundTop && roundBottom) {
                g2d.fillRoundRect(0, rectY, clipWidth, rectHeight, CORNER_RADIUS * 2, CORNER_RADIUS * 2)
            } else if (roundTop) {
                g2d.fillRoundRect(0, rectY, clipWidth, rectHeight + CORNER_RADIUS, CORNER_RADIUS * 2, CORNER_RADIUS * 2)
                g2d.fillRect(0, rectY + rectHeight - CORNER_RADIUS, clipWidth, CORNER_RADIUS)
            } else if (roundBottom) {
                g2d.fillRect(0, rectY, clipWidth, CORNER_RADIUS)
                g2d.fillRoundRect(
                    0,
                    rectY - CORNER_RADIUS,
                    clipWidth,
                    rectHeight + CORNER_RADIUS,
                    CORNER_RADIUS * 2,
                    CORNER_RADIUS * 2
                )
            } else {
                g2d.fillRect(0, rectY, clipWidth, rectHeight)
            }

            // Draw left border accent (slightly rounded at corners)
            val accentWidth = JBUI.scale(3)
            g2d.color = repoColor
            if (roundTop && roundBottom) {
                g2d.fillRoundRect(0, rectY, accentWidth, rectHeight, CORNER_RADIUS, CORNER_RADIUS)
            } else if (roundTop) {
                g2d.fillRoundRect(0, rectY, accentWidth, rectHeight + CORNER_RADIUS, CORNER_RADIUS, CORNER_RADIUS)
                g2d.fillRect(0, rectY + rectHeight - CORNER_RADIUS, accentWidth, CORNER_RADIUS)
            } else if (roundBottom) {
                g2d.fillRect(0, rectY, accentWidth, CORNER_RADIUS)
                g2d.fillRoundRect(
                    0,
                    rectY - CORNER_RADIUS,
                    accentWidth,
                    rectHeight + CORNER_RADIUS,
                    CORNER_RADIUS,
                    CORNER_RADIUS
                )
            } else {
                g2d.fillRect(0, rectY, accentWidth, rectHeight)
            }

            // Draw repo name (truncated to fit within column bounds)
            val textX = JBUI.scale(6)
            val availableWidth = clipWidth - textX - JBUI.scale(2) // Leave small margin on right
            val smallFont = g2d.font.deriveFont(g2d.font.size2D * 0.85f)
            g2d.font = smallFont
            g2d.color = table.foreground

            val displayName = entry.repo.displayName
            val fm = g2d.fontMetrics
            val truncatedText = truncateText(displayName, fm, availableWidth)
            g2d.drawString(truncatedText, textX, getTextBaseLine(fm, height))
        }

        /**
         * Truncate text to fit within available width, adding ellipsis if needed.
         */
        private fun truncateText(text: String, fm: java.awt.FontMetrics, availableWidth: Int): String {
            if (availableWidth <= 0) return ""
            if (fm.stringWidth(text) <= availableWidth) return text

            val ellipsis = "..."
            val ellipsisWidth = fm.stringWidth(ellipsis)
            if (ellipsisWidth >= availableWidth) return ""

            var truncated = text
            while (truncated.isNotEmpty() && fm.stringWidth(truncated) + ellipsisWidth > availableWidth) {
                truncated = truncated.dropLast(1)
            }
            return if (truncated.isEmpty()) "" else truncated + ellipsis
        }

        @Suppress("UNUSED_PARAMETER")
        private fun paintCollapsed(g2d: Graphics2D, entry: LogEntry, repoColor: java.awt.Color, clipWidth: Int) {
            val model = table.model as? JujutsuLogTableModel ?: return

            // Check if previous/next rows are from the same repo
            val prevEntry = if (row > 0) model.getEntry(row - 1) else null
            val nextEntry = model.getEntry(row + 1)

            val sameAsPrev = prevEntry?.repo == entry.repo
            val sameAsNext = nextEntry?.repo == entry.repo

            // Calculate bar position and size (use clipWidth for centering)
            val barX = (clipWidth - BAR_WIDTH) / 2
            var barY = 0
            var barHeight = height

            // Add gaps at top/bottom if different repo
            if (!sameAsPrev) {
                barY = GAP_SIZE
                barHeight -= GAP_SIZE
            }
            if (!sameAsNext) {
                barHeight -= GAP_SIZE
            }

            // Draw the bar with rounded corners
            g2d.color = repoColor

            // Determine which corners to round
            val roundTop = !sameAsPrev
            val roundBottom = !sameAsNext

            if (roundTop && roundBottom) {
                // Both ends rounded
                g2d.fillRoundRect(barX, barY, BAR_WIDTH, barHeight, CORNER_RADIUS * 2, CORNER_RADIUS * 2)
            } else if (roundTop) {
                // Top rounded, bottom flat
                g2d.fillRoundRect(
                    barX,
                    barY,
                    BAR_WIDTH,
                    barHeight + CORNER_RADIUS,
                    CORNER_RADIUS * 2,
                    CORNER_RADIUS * 2
                )
                g2d.fillRect(barX, barY + barHeight - CORNER_RADIUS, BAR_WIDTH, CORNER_RADIUS)
            } else if (roundBottom) {
                // Top flat, bottom rounded
                g2d.fillRect(barX, barY, BAR_WIDTH, CORNER_RADIUS)
                g2d.fillRoundRect(
                    barX,
                    barY - CORNER_RADIUS,
                    BAR_WIDTH,
                    barHeight + CORNER_RADIUS,
                    CORNER_RADIUS * 2,
                    CORNER_RADIUS * 2
                )
            } else {
                // Both ends flat (continuous bar)
                g2d.fillRect(barX, barY, BAR_WIDTH, barHeight)
            }
        }

        override fun getPreferredSize() = super.getPreferredSize().apply {
            // Use actual column width, not a fixed constant
            width = columnWidth
        }
    }
}
