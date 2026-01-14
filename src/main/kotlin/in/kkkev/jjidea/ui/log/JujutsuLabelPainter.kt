package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.ui.GraphicsConfig
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBValue
import `in`.kkkev.jjidea.jj.Bookmark
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import javax.swing.JComponent

/**
 * Utility for painting JJ bookmark labels with platform-native styling.
 *
 * Adapted from IntelliJ Platform's internal LabelPainter (vcs-log-impl).
 * Renders bookmark labels with icons, text, and proper spacing/theming.
 */
class JujutsuLabelPainter(
    private val component: JComponent,
    private val compact: Boolean = false
) {
    companion object {
        private val TOP_TEXT_PADDING = JBValue.UIInteger("VersionControl.Log.Commit.verticalPadding", 1)
        private val BOTTOM_TEXT_PADDING = JBValue.UIInteger("VersionControl.Log.Commit.verticalPadding", 2)
        private val LEFT_PADDING = JBValue.UIInteger("VersionControl.Log.Commit.horizontalPadding", 4)
        private val RIGHT_PADDING = JBValue.UIInteger("VersionControl.Log.Commit.horizontalPadding", 4)
        private val COMPACT_MIDDLE_PADDING = JBValue.UIInteger("VersionControl.Log.Commit.compactSpacing", 2)
        private val MIDDLE_PADDING = JBValue.UIInteger("VersionControl.Log.Commit.spacing", 12)
        private val ICON_TEXT_PADDING = JBValue.UIInteger("VersionControl.Log.Commit.iconTextSpacing", 1)
        private val LABEL_ARC = JBValue.UIInteger("VersionControl.Log.Commit.labelArc", 6)

        private const val MAX_BOOKMARK_LENGTH = 22
        private const val ELLIPSIS = "..."

        private fun getIconTextPadding() = if (ExperimentalUI.isNewUI()) ICON_TEXT_PADDING.get() else 0
    }

    /**
     * Calculate total width needed to paint the given bookmarks.
     */
    fun calculateWidth(bookmarks: List<Bookmark>, baseFontMetrics: java.awt.FontMetrics): Int {
        if (bookmarks.isEmpty()) return 0

        // Use smaller font metrics to match actual rendering
        val baseFont = component.font.deriveFont(Font.PLAIN)
        val smallerFont = baseFont.deriveFont(baseFont.size2D * 0.9f)
        val fontMetrics = component.getFontMetrics(smallerFont)

        var width = LEFT_PADDING.get() + RIGHT_PADDING.get()
        val iconHeight = fontMetrics.height
        val icon = JujutsuBookmarkIcon(iconHeight)

        for ((index, bookmark) in bookmarks.withIndex()) {
            val isLast = index == bookmarks.size - 1

            // Icon width
            width += icon.iconWidth
            width += getIconTextPadding()

            // Text width
            val text = shortenBookmarkName(bookmark.name, fontMetrics)
            width += fontMetrics.stringWidth(text)

            // Spacing between labels
            if (!isLast) {
                width += if (compact) COMPACT_MIDDLE_PADDING.get() else MIDDLE_PADDING.get()
            }
        }

        return width
    }

    /**
     * Paint bookmark labels at the specified position.
     *
     * @param g2 Graphics context
     * @param x Starting X coordinate
     * @param y Starting Y coordinate
     * @param height Total height available
     * @param bookmarks List of bookmarks to render
     * @param background Background color
     * @param foreground Foreground color for text
     * @param isSelected Whether the row is selected
     * @return X coordinate after painting (for chaining)
     */
    fun paint(
        g2: Graphics2D,
        x: Int,
        y: Int,
        height: Int,
        bookmarks: List<Bookmark>,
        background: Color,
        foreground: Color,
        isSelected: Boolean
    ): Int {
        if (bookmarks.isEmpty()) return x

        val config: GraphicsConfig = GraphicsUtil.setupAAPainting(g2)
        try {
            // Use smaller font for bookmark text to match IntelliJ style
            val baseFont = component.font.deriveFont(Font.PLAIN)
            val smallerFont = baseFont.deriveFont(baseFont.size2D * 0.9f)
            g2.font = smallerFont
            val fontMetrics = g2.fontMetrics
            val baseLine = SimpleColoredComponent.getTextBaseLine(fontMetrics, height)
            val iconHeight = fontMetrics.height

            // Use grey text color (not orange) - matching IntelliJ's branch/tag rendering
            val textColor = if (isSelected) {
                foreground
            } else {
                JBColor.GRAY
            }

            var currentX = x + LEFT_PADDING.get()

            for ((index, bookmark) in bookmarks.withIndex()) {
                val icon = JujutsuBookmarkIcon(iconHeight)
                val text = shortenBookmarkName(bookmark.name, fontMetrics)
                val isLast = index == bookmarks.size - 1

                // Paint icon (orange color is in the icon itself)
                icon.paintIcon(component, g2, currentX, y + (height - icon.iconHeight) / 2)
                currentX += icon.iconWidth + getIconTextPadding()

                // Paint text with grey color
                g2.color = textColor
                g2.drawString(text, currentX, y + baseLine)
                currentX += fontMetrics.stringWidth(text)

                // Add spacing between labels
                if (!isLast) {
                    currentX += if (compact) COMPACT_MIDDLE_PADDING.get() else MIDDLE_PADDING.get()
                }
            }

            currentX += RIGHT_PADDING.get()
            return currentX
        } finally {
            config.restore()
        }
    }

    /**
     * Paint bookmark labels right-aligned from a given right edge.
     *
     * @param g2 Graphics context
     * @param rightX Right edge X coordinate (labels will be painted to the left of this)
     * @param y Starting Y coordinate
     * @param height Total height available
     * @param bookmarks List of bookmarks to render
     * @param background Background color
     * @param foreground Foreground color for text
     * @param isSelected Whether the row is selected
     * @return X coordinate where painting started (leftmost point)
     */
    fun paintRightAligned(
        g2: Graphics2D,
        rightX: Int,
        y: Int,
        height: Int,
        bookmarks: List<Bookmark>,
        background: Color,
        foreground: Color,
        isSelected: Boolean
    ): Int {
        if (bookmarks.isEmpty()) return rightX

        val fontMetrics = g2.fontMetrics
        val totalWidth = calculateWidth(bookmarks, fontMetrics)
        val startX = rightX - totalWidth

        paint(g2, startX, y, height, bookmarks, background, foreground, isSelected)
        return startX
    }

    private fun shortenBookmarkName(name: String, fontMetrics: java.awt.FontMetrics): String {
        if (name.length <= MAX_BOOKMARK_LENGTH) return name

        // Simple truncation with ellipsis
        val maxWidth = fontMetrics.stringWidth("M".repeat(MAX_BOOKMARK_LENGTH))
        if (fontMetrics.stringWidth(name) <= maxWidth) return name

        // Binary search for right length
        var truncated = name
        while (truncated.isNotEmpty() && fontMetrics.stringWidth(truncated + ELLIPSIS) > maxWidth) {
            truncated = truncated.dropLast(1)
        }

        return if (truncated.isEmpty()) name.take(MAX_BOOKMARK_LENGTH) + ELLIPSIS else truncated + ELLIPSIS
    }
}
