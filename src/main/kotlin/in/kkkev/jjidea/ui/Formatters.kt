package `in`.kkkev.jjidea.ui

import com.intellij.util.ui.UIUtil

/**
 * HTML formatting utilities for Jujutsu UI elements.
 * Provides consistent rendering across the plugin.
 */
object Formatters {
    /**
     * Escape HTML special characters.
     */
    fun escapeHtml(text: String): String =
        text
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
