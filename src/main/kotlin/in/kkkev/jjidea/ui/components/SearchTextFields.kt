package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SearchTextField
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/** Width of the leading search/history icon zone, as [SearchTextField] itself treats it (before scaling). */
private const val SEARCH_ICON_WIDTH = 28

/**
 * A [SearchTextField] whose history survives IDE restarts (stored under [historyKey], shared by every
 * field with the same key) and whose history icon shows the arrow pointer rather than the text cursor.
 *
 * [popupAnchor] is the component the history drop-down opens beneath, or null for the field itself. A
 * field inside a popup passes the popup's content: the drop-down is a popup of its own, and opened under
 * the field it overlaps the parent popup and ends up behind it.
 */
fun persistentHistorySearchField(historyKey: String, popupAnchor: () -> Component? = { null }): SearchTextField =
    object : SearchTextField(true, historyKey) {
        override fun getPopupLocationComponent(): Component = popupAnchor() ?: this
    }.apply { useArrowCursorOverIcon() }

/**
 * Shows the arrow over the leading icon (which opens the history) instead of the text cursor. The icon
 * zone is the left [SEARCH_ICON_WIDTH]px, as in [SearchTextField]; inside a `SearchFieldWithExtension`
 * the editor's own border is empty, so its insets can't be used to find it. [SearchTextField] resets
 * the cursor on every mouse event before listeners run, so it is set again after each one.
 */
private fun SearchTextField.useArrowCursorOverIcon() {
    val editor = textEditor
    val update = object : MouseAdapter() {
        override fun mouseMoved(e: MouseEvent) = apply(e)

        override fun mouseEntered(e: MouseEvent) = apply(e)

        override fun mousePressed(e: MouseEvent) = apply(e)

        override fun mouseReleased(e: MouseEvent) = apply(e)

        override fun mouseClicked(e: MouseEvent) = apply(e)

        private fun apply(e: MouseEvent) {
            editor.cursor = Cursor.getPredefinedCursor(
                if (e.x < JBUI.scale(SEARCH_ICON_WIDTH)) Cursor.DEFAULT_CURSOR else Cursor.TEXT_CURSOR
            )
        }
    }
    editor.addMouseListener(update)
    editor.addMouseMotionListener(update)
}
