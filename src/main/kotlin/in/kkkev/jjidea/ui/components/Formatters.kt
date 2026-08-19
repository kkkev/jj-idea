package `in`.kkkev.jjidea.ui.components

import com.intellij.util.ui.UIUtil

/**
 * HTML formatting utilities for Jujutsu UI elements.
 * Provides consistent rendering across the plugin.
 */
object Formatters {
    /**
     * Escape HTML special characters. Does *not* touch regular spaces - a blanket
     * space-to-`&nbsp;` substitution here once broke line-wrapping and copy/paste fidelity for
     * ordinary text like a description (jj-idea-myje / GitHub #77), since this is the single
     * escaping path for all HTML-rendered text. Deliberate non-collapsing gaps (e.g. between an
     * icon and its label, or between two chips) are the caller's responsibility via
     * [TextCanvas.space] instead.
     */
    fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("\n", "<br/>")

    /**
     * Get base font styling for HTML body.
     */
    fun getBodyStyle(): String {
        val font = UIUtil.getLabelFont()
        return "font-family: ${font.family}; font-size: ${font.size}pt;"
    }
}
