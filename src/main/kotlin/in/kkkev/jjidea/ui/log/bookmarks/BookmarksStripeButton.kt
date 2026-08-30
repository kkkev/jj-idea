package `in`.kkkev.jjidea.ui.log.bookmarks

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * Always-visible clickable strip that toggles the bookmarks panel (jj-idea-b2ae), pinned outside
 * the panel's own collapsible splitter (see
 * [in.kkkev.jjidea.ui.common.CommitTablePanel.installLeftComponent]).
 *
 * Exists because a plain splitter whose `firstComponent` goes `null` when collapsed leaves nothing
 * on screen to click to bring it back — discovering the panel again would require remembering the
 * View Options menu entry. This mirrors the always-visible strip of the multi-repo root gutter
 * ([in.kkkev.jjidea.ui.log.JujutsuRootGutterRenderer]: never fully hidden, just thinner when
 * collapsed) rather than git4idea's rotated-text `ExpandStripeButton`, which needs a custom
 * `ButtonUI` this codebase has no other use for.
 */
class BookmarksStripeButton(private val onClick: () -> Unit) : JPanel(BorderLayout()) {
    private val label = JBLabel(JujutsuIcons.Bookmark)

    init {
        border = JBUI.Borders.empty(4, 2)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(label, BorderLayout.NORTH)
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick()
            }
        )
        setExpanded(true)
    }

    /** Updates the tooltip to describe what a click will do next. */
    fun setExpanded(expanded: Boolean) {
        val key = if (expanded) "bookmarks.panel.hide" else "bookmarks.panel.expand"
        toolTipText = JujutsuBundle.message(key)
    }
}
