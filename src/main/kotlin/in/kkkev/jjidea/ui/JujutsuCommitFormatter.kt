package `in`.kkkev.jjidea.ui

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.NamedColorUtil
import `in`.kkkev.jjidea.jj.ChangeId

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

    fun format(changeId: ChangeId) = FormattedChangeId(changeId.short, changeId.displayRemainder)

    /**
     * Format change ID as HTML matching the column style
     * (bold for short prefix, grayed/smaller for remainder)
     * @param formatted The formatted change ID
     * @return HTML string with styled parts
     */
    fun toHtml(formatted: FormattedChangeId): String {
        val grayColor = ColorUtil.toHtmlColor(NamedColorUtil.getInactiveTextColor())
        return buildString {
            append("<b>${formatted.shortPart}</b>")
            if (formatted.restPart.isNotEmpty()) {
                append("<font color=$grayColor><small>${formatted.restPart}</small></font>")
            }
        }
    }

}
