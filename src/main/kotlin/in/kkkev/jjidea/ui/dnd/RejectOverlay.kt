package `in`.kkkev.jjidea.ui.dnd

import com.intellij.ide.dnd.DnDEvent
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

/**
 * A "blocked" indicator painted entirely outside `DnDEvent`'s own highlighter mechanism - see
 * [show]'s doc for why. Lives for the duration of one `installDragAndDrop` call; [hide] is
 * idempotent and cheap to call on every mouse-move tick where nothing should be shown.
 *
 * Retyped against plain [JComponent] (not a specific table/tree class) so every drop-target
 * surface can share one implementation - jj-idea-ymuu's finding that the native reject cursor is
 * not reliable feedback applies to a tree exactly as much as to a table (batch 4,
 * jj-idea-0rdm), since drag-over ticks are OS-coalesced regardless of which component they land on.
 */
class RejectOverlay {
    private var panel: JComponent? = null

    /**
     * Paint a filled, error-colored rectangle at [rect] ([component]-relative) on top of
     * [component] - a real guard rejection (cross-repo, cycle, immutable) needs its own reliable
     * indicator, because the platform's native reject cursor turned out not to be one on its own
     * (jj-idea-ymuu): in-app drag-over ticks are OS-coalesced, so a fast or even a deliberately
     * slow drag across a rejected target could show nothing at all.
     *
     * This can't reuse [DnDEvent.setHighlighting] (as an allowed-drop indicator does) the way a
     * first attempt at this fix did: `DnDManagerImpl.updateCurrentEvent` unconditionally calls
     * `hideCurrentHighlighter()` on every tick where the point differs and `isDropPossible()` is
     * false (no `Highlighters.isVisibleExcept` guard, unlike the `isDropPossible() == true`
     * branch) - the only thing re-queued afterward is a delayed, registry-gated
     * (`ide.dnd.textHints`) `ERROR_TEXT` balloon, never the `RECTANGLE`/`FILLED_RECTANGLE` we just
     * painted. So any highlighter set while `dropPossible` is false gets wiped by the platform
     * itself on the very next tick, which is exactly the "flashes once, then never again" seen in
     * manual testing. Painting our own component directly into the same layered pane
     * (`Highlighters`' own components use) sidesteps that bookkeeping entirely.
     */
    fun show(component: JComponent, rect: Rectangle) {
        val layeredPane = SwingUtilities.getRootPane(component)?.layeredPane ?: return
        val current = panel ?: RejectPanel().also {
            panel = it
            layeredPane.add(it, JLayeredPane.DRAG_LAYER)
        }
        if (current.parent !== layeredPane) {
            current.parent?.remove(current)
            layeredPane.add(current, JLayeredPane.DRAG_LAYER)
        }
        val topLeft = SwingUtilities.convertPoint(component, rect.location, layeredPane)
        current.setBounds(topLeft.x, topLeft.y, rect.width, rect.height)
        current.isVisible = true
    }

    fun hide() {
        panel?.isVisible = false
    }

    /** Remove the overlay component from its layered pane for good - called when the surface is disposed. */
    fun dispose() {
        panel?.let { it.parent?.remove(it) }
        panel = null
    }

    private class RejectPanel : JComponent() {
        init {
            isOpaque = false
        }

        // Translucent fill (not the opaque errorBackgroundColor() itself) - a full-strength fill
        // completely hid the row's own text underneath it, which defeats the point of an
        // indicator that's supposed to name what's being rejected (jj-idea-ymuu follow-up).
        override fun paintComponent(g: Graphics) {
            g.color = ColorUtil.withAlpha(JBUI.CurrentTheme.Validator.errorBackgroundColor(), 0.55)
            g.fillRect(0, 0, width, height)
            g.color = JBUI.CurrentTheme.Validator.errorBorderColor()
            g.drawRect(0, 0, width - 1, height - 1)
        }
    }
}
