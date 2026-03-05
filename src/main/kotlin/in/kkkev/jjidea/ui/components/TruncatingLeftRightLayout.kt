package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SimpleColoredComponent
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas.Fragment
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BoxLayout
import javax.swing.BoxLayout.X_AXIS
import javax.swing.JLabel
import javax.swing.JPanel

class TextCanvasPanel : JPanel() {
    init {
        layout = BoxLayout(this, X_AXIS)
    }

    /**
     * Render fragments into this panel using [BoxLayout]. Text fragments are appended to
     * [SimpleColoredComponent]s; icon fragments become [JLabel]s. Adjacent text fragments
     * share the same SCC to avoid unnecessary component boundaries.
     */
    fun renderFrom(canvas: FragmentRecordingCanvas) {
        removeAll()
        this.layout = BoxLayout(this, X_AXIS)
        var currentScc: SimpleColoredComponent? = null
        for (fragment in canvas.fragments) {
            when (fragment) {
                is Fragment.Text -> {
                    if (currentScc == null) {
                        currentScc = SimpleColoredComponent().also {
                            it.isOpaque = false
                            add(it)
                        }
                    }
                    currentScc.append(fragment.text, fragment.style)
                }

                is Fragment.Icon -> {
                    currentScc = null
                    IconResolver.resolveIcon(fragment.icon.qualified)?.let { icon ->
                        add(JLabel(icon).also { it.isOpaque = false })
                    }
                }
            }
        }
    }
}

/**
 * A panel that lays out a left side (fills remaining space) and a right side (sizes to content)
 * using [BorderLayout].
 *
 * Both sides are populated from [FragmentRecordingCanvas] instances. The left canvas's truncatable
 * region is shortened to fit the space remaining after the right side. Fragments render as a mix
 * of [com.intellij.ui.SimpleColoredComponent] (text) and [javax.swing.JLabel] (icons) — no custom
 * `paintComponent`.
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
     */
    fun configure(
        leftCanvas: FragmentRecordingCanvas,
        rightCanvas: FragmentRecordingCanvas,
        cellWidth: Int,
        background: Color
    ) {
        this.background = background

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
