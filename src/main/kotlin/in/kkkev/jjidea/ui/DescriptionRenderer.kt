package `in`.kkkev.jjidea.ui

import com.intellij.ui.SimpleTextAttributes
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Description

/**
 * Common renderer for Description objects across the plugin.
 * Provides consistent formatting for empty and non-empty descriptions.
 */
object DescriptionRenderer {
    /**
     * Render description to a ColoredListCellRenderer or similar component
     * @param description The description to render
     * @param append Function to append text with attributes (e.g., from ColoredListCellRenderer)
     */
    fun renderToComponent(description: Description, append: (String, SimpleTextAttributes) -> Unit) {
        if (description.empty) {
            append(JujutsuBundle.message("description.empty"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        } else {
            append(description.summary, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    /**
     * Get plain text representation of description for tooltips
     * @param description The description to render
     * @return Plain text representation
     */
    fun toPlainText(description: Description): String =
        if (description.empty) JujutsuBundle.message("description.empty") else description.actual

    /**
     * Get HTML representation of description for tooltips
     * @param description The description to render
     * @param multiline If true, convert newlines to <br> tags
     * @return HTML-formatted description
     */
    fun toHtml(description: Description, multiline: Boolean = true): String = when {
        description.empty -> "<i>${JujutsuBundle.message("description.empty")}</i>"
        multiline -> description.actual.replace("\n", "<br>")
        else -> description.summary
    }

    /**
     * Get display text for a description first line
     * Used in annotations and other line-based displays
     */
    fun toDisplayText(descriptionFirstLine: String): String =
        descriptionFirstLine.ifEmpty { JujutsuBundle.message("description.empty") }
}
