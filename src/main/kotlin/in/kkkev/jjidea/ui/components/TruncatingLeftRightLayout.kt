package `in`.kkkev.jjidea.ui.components

import java.awt.BorderLayout
import java.awt.Color
import javax.swing.BoxLayout
import javax.swing.JPanel

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
    val left = JPanel().also { it.layout = BoxLayout(it, BoxLayout.X_AXIS) }
    val right = JPanel().also { it.layout = BoxLayout(it, BoxLayout.X_AXIS) }

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

        FragmentLayout.renderToPanel(rightCanvas.fragments, right)
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
        FragmentLayout.renderToPanel(truncated, left)
    }
}
