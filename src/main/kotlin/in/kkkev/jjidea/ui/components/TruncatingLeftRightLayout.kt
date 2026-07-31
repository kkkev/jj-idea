package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.ui.common.ScaledIcon
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas.Fragment
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.BoxLayout
import javax.swing.BoxLayout.X_AXIS
import javax.swing.JLabel
import javax.swing.JPanel

/** Scale factor applied to icons inside `smaller { }` blocks, matching the text scale in [FragmentLayout]. */
internal const val SMALLER_SCALE = 0.85f

/** Client property key identifying which [Fragment.linkTarget] a rendered child component belongs to (jj-idea-a52h). */
private const val LINK_TARGET_KEY = "jjLinkTarget"

open class TextCanvasPanel : JPanel() {
    init {
        layout = BoxLayout(this, X_AXIS)
    }

    /**
     * The [Fragment.linkTarget] to paint a hover-highlight background behind (jj-idea-a52h) - a
     * greyish "list hover" tint, the same visual cue used for right-clickable items elsewhere in
     * the IDE (e.g. selecting a list row), signalling "this has a right-click menu" now that
     * bookmark/tag chips are no longer left-click hyperlinks (jj-idea-wkcz). Null paints nothing.
     */
    var highlightTarget: Any? = null

    /**
     * Render fragments into this panel using [BoxLayout]. Text fragments are appended to
     * [SimpleColoredComponent]s; icon fragments become [JLabel]s. Adjacent fragments sharing the
     * same [Fragment.linkTarget] share one SCC/JLabel-group boundary, so [highlightTarget] can find
     * the exact child components belonging to one chip by that single shared client property,
     * rather than the highlight bleeding into a neighboring separator or chip.
     */
    fun renderFrom(canvas: FragmentRecordingCanvas) {
        ApplicationManager.getApplication()?.assertIsDispatchThread()
        removeAll()
        this.layout = BoxLayout(this, X_AXIS)
        var currentScc: SimpleColoredComponent? = null
        var currentSccTarget: Any? = null
        for (fragment in canvas.fragments) {
            when (fragment) {
                is Fragment.Text -> {
                    if (currentScc == null || fragment.linkTarget != currentSccTarget) {
                        currentSccTarget = fragment.linkTarget
                        currentScc = SimpleColoredComponent().also {
                            it.isOpaque = false
                            it.putClientProperty(LINK_TARGET_KEY, currentSccTarget)
                            add(it)
                        }
                    }
                    currentScc.append(fragment.text, fragment.style, fragment.linkTarget)
                }

                is Fragment.Icon -> {
                    currentScc = null
                    currentSccTarget = null
                    IconResolver.resolveIcon(fragment.icon.qualified)?.let { icon ->
                        val scaled = if (fragment.style.isSmaller) ScaledIcon(icon, SMALLER_SCALE) else icon
                        add(
                            JLabel(scaled).also {
                                it.isOpaque = false
                                if (fragment.linkTarget != null) {
                                    it.putClientProperty(LINK_TARGET_KEY, fragment.linkTarget)
                                }
                            }
                        )
                    }
                }
            }
        }
        // Cap each component's max width at its preferred width so BoxLayout cannot
        // redistribute horizontal space across siblings (which would centre-justify content).
        components.forEach { it.maximumSize = Dimension(it.preferredSize.width, Short.MAX_VALUE.toInt()) }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val target = highlightTarget ?: return
        val bounds = components
            .filterIsInstance<javax.swing.JComponent>()
            .filter { it.getClientProperty(LINK_TARGET_KEY) == target }
            .map { it.bounds }
            .reduceOrNull { a, b -> a.union(b) }
            ?: return
        g.color = UIUtil.getListBackground(true, false)
        g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 4, 4)
    }
}

/**
 * A panel that lays out a left side (fills remaining space) and a right side (sizes to content)
 * using [BorderLayout].
 *
 * Both sides are populated from [FragmentRecordingCanvas] instances. The left canvas's truncatable
 * region is shortened to fit the space remaining after the right side. Fragments render as a mix
 * of [com.intellij.ui.SimpleColoredComponent] (text) and [javax.swing.JLabel] (icons); [TextCanvasPanel]'s
 * only custom painting is [TextCanvasPanel.highlightTarget]'s hover background (jj-idea-a52h).
 */
class TruncatingLeftRightLayout : JPanel(BorderLayout(0, 0)) {
    val left = TextCanvasPanel()
    val right = TextCanvasPanel()

    init {
        isOpaque = true
        left.isOpaque = false
        right.isOpaque = false
        add(left, BorderLayout.CENTER)
        add(right, BorderLayout.EAST)
    }

    /**
     * Configure the panel for a single table cell.
     *
     * 1. Renders [rightCanvas] fragments into the right panel
     * 2. Measures right panel's preferred width
     * 3. Truncates [leftCanvas] fragments to fit `cellWidth - rightWidth`
     * 4. Renders truncated fragments into the left panel
     *
     * @param rightHighlightTarget the [FragmentRecordingCanvas.Fragment.linkTarget] (if any) to
     * paint a hover-highlight background behind in the right panel - the currently-hovered
     * bookmark/tag chip's ref URI, or null for no highlight (jj-idea-a52h).
     */
    fun configure(
        leftCanvas: FragmentRecordingCanvas,
        rightCanvas: FragmentRecordingCanvas,
        cellWidth: Int,
        background: Color,
        rightHighlightTarget: Any? = null
    ) {
        this.background = background

        right.highlightTarget = rightHighlightTarget
        right.renderFrom(rightCanvas)
        val rightWidth = right.preferredSize.width

        val frc = getFontMetrics(font).fontRenderContext
        val availableForLeft = (cellWidth - rightWidth).toDouble()
        val truncated = FragmentLayout.truncateToFit(
            leftCanvas.fragments,
            leftCanvas.truncateRange,
            availableForLeft,
            font,
            frc
        )
        left.renderFrom(FragmentRecordingCanvas(truncated))
    }
}
