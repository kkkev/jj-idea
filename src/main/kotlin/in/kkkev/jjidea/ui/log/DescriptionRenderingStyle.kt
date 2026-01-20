package `in`.kkkev.jjidea.ui.log

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import java.awt.Color
import java.awt.Font

/**
 * Shared logic for determining how to render descriptions across different renderers.
 * This avoids duplicating the bold/italic/color logic between ColoredTableCellRenderer
 * and the custom Graphics2D-based combined renderer.
 */
object DescriptionRenderingStyle {
    /**
     * Determine the text attributes for a description in ColoredTableCellRenderer.
     */
    fun getTextAttributes(description: Description, isWorkingCopy: Boolean): SimpleTextAttributes = when {
        description.empty && isWorkingCopy ->
            SimpleTextAttributes(
                SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_ITALIC,
                SimpleTextAttributes.GRAY_ATTRIBUTES.fgColor
            )

        description.empty -> SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES
        isWorkingCopy -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
    }

    /**
     * Determine the text attributes for a full LogEntry (includes description).
     */
    fun getTextAttributes(entry: LogEntry): SimpleTextAttributes =
        getTextAttributes(entry.description, entry.isWorkingCopy)

    /**
     * Determine the font style for Graphics2D rendering.
     */
    fun getFontStyle(description: Description, isWorkingCopy: Boolean): Int = when {
        description.empty && isWorkingCopy -> Font.BOLD or Font.ITALIC
        description.empty -> Font.ITALIC
        isWorkingCopy -> Font.BOLD
        else -> Font.PLAIN
    }

    /**
     * Determine the font style for a full LogEntry.
     */
    fun getFontStyle(entry: LogEntry): Int = getFontStyle(entry.description, entry.isWorkingCopy)

    /**
     * Determine the text color for Graphics2D rendering.
     */
    fun getTextColor(
        description: Description,
        isSelected: Boolean,
        selectionForeground: Color,
        defaultForeground: Color
    ): Color = if (description.empty) {
        if (isSelected) selectionForeground else JBColor.GRAY
    } else {
        if (isSelected) selectionForeground else defaultForeground
    }

    /**
     * Determine the font style for the "(empty)" indicator in Graphics2D rendering.
     */
    fun getEmptyIndicatorFontStyle(isWorkingCopy: Boolean): Int = if (isWorkingCopy) {
        Font.BOLD or Font.ITALIC
    } else {
        Font.ITALIC
    }
}
