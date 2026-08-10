package `in`.kkkev.jjidea.ui.common

import com.intellij.ui.JBColor

/**
 * Consistent color palette for Jujutsu plugin UI.
 * Centralizes all color definitions to ensure consistency across the plugin.
 */
object JujutsuColors {
    /**
     * Working copy indicator color (@).
     *
     * Contrast-checked against panel backgrounds (>=4.5:1) — see [JujutsuColorsContrastTest].
     */
    val WORKING_COPY = JBColor(0x2A5AAE, 0x7EA6F0)

    /**
     * Bookmark/reference color.
     *
     * Contrast-checked against panel backgrounds (>=4.5:1) — see [JujutsuColorsContrastTest].
     */
    val BOOKMARK = JBColor(0x7A5D00, 0xD4A574)

    /**
     * Tag color (distinct from bookmark gold).
     *
     * Contrast-checked against panel backgrounds (>=4.5:1) — see [JujutsuColorsContrastTest].
     */
    val TAG = JBColor(0x256B29, 0x5FA85F)

    /**
     * Conflict indicator color.
     *
     * Contrast-checked against panel backgrounds (>=4.5:1) — see [JujutsuColorsContrastTest].
     */
    val CONFLICT = JBColor(0xC00000, 0xFF6464)

    /**
     * Contrast-checked against panel backgrounds (>=4.5:1) — see [JujutsuColorsContrastTest].
     */
    val DIVERGENT = JBColor(0xB0006E, 0xFFAFAF)

    /**
     * Highlight for source changes for a rebase operation.
     */
    val SOURCE_HIGHLIGHT = JBColor(0x34A853, 0x5DCD73)

    /**
     * Highlight for destination changes for a rebase operation.
     */
    val DESTINATION_HIGHLIGHT = JBColor(0x4285F4, 0x6AA1FF)
}
