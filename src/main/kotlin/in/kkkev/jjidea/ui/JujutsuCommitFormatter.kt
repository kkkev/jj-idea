package `in`.kkkev.jjidea.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.NamedColorUtil
import `in`.kkkev.jjidea.jj.ChangeId
import java.awt.Color

/**
 * Formats commit identifiers in the jj way:
 * - Short unique prefix is distinguishable (for bold display in UI)
 * - Working copy shows both commit name and @
 */
object JujutsuCommitFormatter {

    /**
     * Represents a formatted change ID with its parts separated
     */
    data class FormattedChangeId(val shortPart: String, val restPart: String) {
        val full: String get() = shortPart + restPart
    }

    /**
     * Colors for change ID formatting (theme-aware)
     */
    object Colors {
        /**
         * Color for the short unique prefix - uses a prominent color that stands out
         * Light mode: Bright magenta for high visibility
         * Dark mode: Bright pink for excellent contrast
         */
        val shortPrefix: Color
            get() = JBColor(Color(200, 0, 200), Color(255, 100, 255))

        /**
         * Color for the remainder - uses inactive/secondary text color from theme
         */
        val remainder: Color
            get() = NamedColorUtil.getInactiveTextColor()
    }

    fun format(changeId: ChangeId) = formatChangeId(changeId.full, changeId.short)

    /**
     * Format a change ID with its short unique prefix, truncating to 8 characters
     * unless the short prefix is longer than 8 characters
     * @param fullChangeId The full change ID (e.g., "qpvuntsm")
     * @param shortPrefix The short unique prefix (e.g., "qp")
     * @return Formatted change ID with separated parts, truncated to max 8 chars
     */
    fun formatChangeId(fullChangeId: String, shortPrefix: String): FormattedChangeId {
        require(fullChangeId.startsWith(shortPrefix)) {
            "Change ID '$fullChangeId' must start with short prefix '$shortPrefix'"
        }

        // Truncate to 8 characters unless short prefix is longer
        val maxLength = maxOf(8, shortPrefix.length)
        val truncatedId = if (fullChangeId.length > maxLength) {
            fullChangeId.substring(0, maxLength)
        } else {
            fullChangeId
        }

        val restPart = truncatedId.substring(shortPrefix.length)
        return FormattedChangeId(shortPrefix, restPart)
    }

    /**
     * Format change ID as HTML with theme-aware colors
     * @param formatted The formatted change ID
     * @param bold Whether to make the short prefix bold (default: true)
     * @return HTML string with colored parts
     */
    fun toHtml(formatted: FormattedChangeId, bold: Boolean = true): String {
        val shortColor = ColorUtil.toHtmlColor(Colors.shortPrefix)
        val restColor = ColorUtil.toHtmlColor(Colors.remainder)
        val boldTag = if (bold) "b" else "span"

        return "<font color=$shortColor><$boldTag>${formatted.shortPart}</$boldTag></font>" +
                "<font color=$restColor>${formatted.restPart}</font>"
    }

    /**
     * Format the working copy display showing both description and @
     * @param changeId The change ID
     * @param shortPrefix The short unique prefix
     * @param description The commit description (empty string if undescribed)
     * @return Formatted string for display
     */
    fun formatWorkingCopy(changeId: String, shortPrefix: String, description: String): String {
        val formatted = formatChangeId(changeId, shortPrefix)
        val desc = description.ifEmpty { "(empty)" }

        return "${formatted.full} @ - $desc"
    }

}
