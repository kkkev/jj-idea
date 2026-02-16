package `in`.kkkev.jjidea.ui.common

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil

/**
 * Consistent color palette for Jujutsu plugin UI.
 * Centralizes all color definitions to ensure consistency across the plugin.
 */
object JujutsuColors {
    /**
     * Working copy indicator color (@).
     */
    val WORKING_COPY = JBColor(0x6494ED, 0x5C84D6)

    /**
     * Bookmark/reference color.
     */
    val BOOKMARK = JBColor(0xF0C674, 0xD4A574)

    /**
     * Conflict indicator color.
     */
    val CONFLICT = JBColor.RED

    val DIVERGENT = JBColor.PINK

    /**
     * Highlight for source changes for a rebase operation.
     */
    val SOURCE_HIGHLIGHT = JBColor(0x34A853, 0x5DCD73)

    /**
     * Highlight for destination changes for a rebase operation.
     */
    val DESTINATION_HIGHLIGHT = JBColor(0x4285F4, 0x6AA1FF)

    /**
     * Get gray color as hex string (for HTML).
     */
    fun getGrayHex(): String = Integer.toHexString(UIUtil.getLabelDisabledForeground().rgb and 0xFFFFFF)

    /**
     * Get link color as hex string (for HTML).
     */
    fun getLinkHex(): String = Integer.toHexString(JBUI.CurrentTheme.Link.Foreground.ENABLED.rgb and 0xFFFFFF)

    /**
     * Get working copy color as hex string (for HTML).
     */
    fun getWorkingCopyHex(): String = Integer.toHexString(WORKING_COPY.rgb and 0xFFFFFF)

    /**
     * Get bookmark color as hex string (for HTML).
     */
    fun getBookmarkHex(): String = Integer.toHexString(BOOKMARK.rgb and 0xFFFFFF)

    /**
     * Get conflict color as hex string (for HTML).
     */
    fun getConflictHex(): String = Integer.toHexString(CONFLICT.rgb and 0xFFFFFF)
}
