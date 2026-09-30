package `in`.kkkev.jjidea.ui.dnd

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.ui.components.IconAwareHtmlPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * A hint next to the cursor - the operation a drop would perform, or why it can't - painted
 * entirely outside `DnDEvent`'s own tooltip mechanism, for the same reason [RejectOverlay] paints its
 * own fill (jj-idea-ymuu).
 *
 * The platform does turn `DnDEvent.setDropPossible(possible, text)`'s text into a balloon, but
 * `DnDManagerImpl.Highlighters` returns early from it unless the `ide.dnd.textHints` registry key is
 * on, and that key defaults to `false`. Every operation label and guard reason we compute was being
 * thrown away, which is why a red reject fill said nothing about *why*. Shows immediately (no
 * tooltip delay timer - drag-over ticks are OS-coalesced, so a delayed hint may never appear).
 *
 * The hint is HTML, rendered from a [DropMessage] into an [IconAwareHtmlPane] - the same pane the
 * log's tooltips use, since bookmark/tag chips are icon and atomic-content elements only it can
 * resolve - so ids, bookmarks and tags look exactly as they do in the log. A text component would
 * normally install a default drop target and swallow a drag passing over it, so the pane's is
 * removed explicitly; the hint is invisible to DnD, like [RejectOverlay].
 *
 * [show] does nothing when `ide.dnd.textHints` is on, so a user who turned the platform hints on
 * doesn't get two. Lives for the duration of one `installDragAndDrop` call; [hide] is idempotent and
 * cheap to call on every mouse-move tick where nothing should be shown.
 */
class DropHint(private val project: Project) {
    private var panel: HintPanel? = null

    /**
     * Show [message] near [point] ([component]-relative, i.e. `DnDEvent.point`). A blank message
     * hides the hint instead - a deliberately silent drop (self-drop, unwired operation) has
     * nothing to say. [rejected] picks the error colours, matching [RejectOverlay]'s fill.
     */
    fun show(component: JComponent, point: Point, message: DropMessage, rejected: Boolean) {
        if (message.isBlank || Registry.`is`(PLATFORM_HINTS_KEY)) {
            hide()
            return
        }
        val layeredPane = SwingUtilities.getRootPane(component)?.layeredPane ?: return
        val offset = JBUI.scale(CURSOR_OFFSET)
        val current = panel ?: HintPanel(project).also { panel = it }
        if (current.parent !== layeredPane) {
            current.parent?.remove(current)
            // add + setLayer, not add(component, HINT_LAYER): in Kotlin an Int second argument binds to
            // Container.add(Component, int index) (a position, not a layer), unlike Java's Integer constant.
            layeredPane.add(current)
            layeredPane.setLayer(current, HINT_LAYER)
        }
        current.update(
            message,
            rejected,
            maxWidth = (layeredPane.width - offset).coerceIn(JBUI.scale(MIN_WIDTH), scaledMaxWidth())
        )
        val size = current.preferredSize
        val anchor = SwingUtilities.convertPoint(component, point, layeredPane)
        // Clamp inside the pane so the hint stays readable at the right/bottom edges; coerceAtLeast
        // last so a pane narrower than the hint pins it to the origin rather than off the left edge.
        val x = (anchor.x + offset).coerceAtMost(layeredPane.width - size.width).coerceAtLeast(0)
        val y = (anchor.y + offset).coerceAtMost(layeredPane.height - size.height).coerceAtLeast(0)
        current.setBounds(x, y, size.width, size.height)
        current.isVisible = true
    }

    fun hide() {
        panel?.isVisible = false
    }

    /** The hint component (visible or not), for tests; `null` until first shown, and after [dispose]. */
    internal val component: JComponent? get() = panel

    /** The plain text currently on show, or `null` when hidden - for tests. */
    internal val shownText: String? get() = panel?.takeIf { it.isVisible }?.message?.plain

    /** The HTML last given to the pane (visible or not), for tests. */
    internal val shownHtml: String? get() = panel?.html

    /** The pane rendering the hint, for tests. */
    internal val pane: IconAwareHtmlPane? get() = panel?.pane

    /** Remove the hint component from its layered pane for good - called when the surface is disposed. */
    fun dispose() {
        panel?.let { it.parent?.remove(it) }
        panel = null
    }

    private class HintPanel(project: Project) : JPanel(BorderLayout()) {
        val pane = IconAwareHtmlPane(project).apply {
            foreground = UIUtil.getToolTipForeground()
            isOpaque = false
            isFocusable = false
            // A text component installs a default DropTarget via its TransferHandler; left in place it
            // would swallow a drag passing over the hint. See the DropHint doc.
            transferHandler = null
            dropTarget = null
        }
        var message: DropMessage? = null
            private set
        var html: String? = null
            private set
        private var rejected = false
        private var maxWidth = 0

        init {
            isOpaque = false
            border = JBUI.Borders.empty(PADDING_Y, PADDING_X) // JBUI.Borders scales itself
            add(pane, BorderLayout.CENTER)
        }

        /**
         * Called on every drag-over tick, but re-setting the pane's HTML rebuilds its view - so only
         * touch it when something it depends on actually changed.
         */
        fun update(message: DropMessage, rejected: Boolean, maxWidth: Int) {
            this.rejected = rejected
            if (message == this.message && maxWidth == this.maxWidth) return
            this.message = message
            this.maxWidth = maxWidth
            val available = maxWidth - 2 * JBUI.scale(PADDING_X)
            val document = "<html>${message.html(refFit(available))}</html>"
            html = document
            pane.text = document
            // Lay the pane out at the widest it may be, then shrink to what it actually needs, so a
            // long message wraps at the cap and a short one hugs its text (as the tooltips do).
            // Clear the explicit size a previous message left behind first, or preferredSize just
            // echoes it back instead of measuring this message.
            pane.preferredSize = null
            pane.setSize(available, Int.MAX_VALUE)
            val preferred = pane.preferredSize
            pane.preferredSize = Dimension(minOf(preferred.width, available), preferred.height)
        }

        /**
         * Fit bookmark/tag chips to [available] pixels, measured rather than counted so it holds at any
         * UI scale: a chip that would leave little room beside its neighbours goes on a line of its own,
         * and one wider than the whole hint is ellipsized to fit. Chips render at `smaller` (85%) of the
         * pane font, with an icon before the name.
         */
        private fun refFit(available: Int): RefFit {
            val font = pane.font
            val metrics = pane.getFontMetrics(font.deriveFont(font.size2D * CHIP_FONT_SCALE))
            val chrome = JBUI.scale(CHIP_ICON_AND_GAP)
            val fitsAlone = (available * FIT_FRACTION).toInt() - chrome
            val inlineMax = (available * INLINE_FRACTION).toInt() - chrome
            return RefFit(
                shorten = { DropMessage.ellipsizeToWidth(it, metrics, fitsAlone) },
                ownLine = { metrics.stringWidth(it) > inlineMax }
            )
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.CurrentTheme.Tooltip.CORNER_RADIUS.get()
                g2.color = if (rejected) {
                    JBUI.CurrentTheme.Validator.errorBackgroundColor()
                } else {
                    JBUI.CurrentTheme.Tooltip.background()
                }
                g2.fillRoundRect(0, 0, width, height, arc, arc)
                g2.color = if (rejected) {
                    JBUI.CurrentTheme.Validator.errorBorderColor()
                } else {
                    JBUI.CurrentTheme.Tooltip.separatorColor()
                }
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        const val PLATFORM_HINTS_KEY = "ide.dnd.textHints"

        // One layer above RejectOverlay's DRAG_LAYER, so the text is never covered by the fill it explains.
        internal val HINT_LAYER = JLayeredPane.DRAG_LAYER + 1

        // Chip geometry for RefFit: the log renders chips at 85% of the ambient font, with a 16px icon
        // plus a couple of pixels of leading gap.
        private const val CHIP_FONT_SCALE = 0.85f
        private const val CHIP_ICON_AND_GAP = 20

        // A chip may use up to this much of the hint's width alone on its line (some slack against
        // metric drift) - and stays inline only if it takes no more than the smaller fraction, leaving
        // room for the words around it.
        private const val FIT_FRACTION = 0.9
        private const val INLINE_FRACTION = 0.4

        private const val CURSOR_OFFSET = 16
        private const val PADDING_X = 8
        private const val PADDING_Y = 4

        // Unscaled (DIP) values - fonts scale with the UI, so the widest chip does too; scale these at
        // use ([scaledMaxWidth] etc.), or a long chip that fits at 100% overflows the bubble at 150%.
        private const val MIN_WIDTH = 120
        internal const val MAX_WIDTH = 480

        internal fun scaledMaxWidth() = JBUI.scale(MAX_WIDTH)
    }
}
