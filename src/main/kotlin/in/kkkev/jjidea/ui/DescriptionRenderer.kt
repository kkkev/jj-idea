package `in`.kkkev.jjidea.ui

import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Description

/**
 * Common renderer for Description objects across the plugin.
 * Provides consistent formatting for empty and non-empty descriptions.
 */
object DescriptionRenderer {
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
}
