package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.ui.GraphicsConfig
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import java.awt.*
import java.awt.geom.Path2D
import javax.swing.Icon

/**
 * Icon for rendering JJ bookmarks in the log UI with platform-native styling.
 *
 * Adapted from IntelliJ Platform's internal BookmarkIcon (vcs-log-impl).
 * Renders as a small bookmark flag/ribbon shape with outline only (not filled).
 */
class JujutsuBookmarkIcon(
    private val size: Int,
    private val color: Color = BOOKMARK_COLOR,
    private val filled: Boolean = false
) : Icon {
    companion object {
        /**
         * Platform theme color for bookmark references.
         * Uses the same color as IntelliJ's VCS bookmarks.
         */
        val BOOKMARK_COLOR: Color =
            JBColor.namedColor(
                "VersionControl.RefLabel.bookmarkBackground",
                JBColor(0xf4af3d, 0xd9a343)
            )

        /** Width multiplier for icon sizing */
        private const val WIDTH_FACTOR = 3.5f

        /** Base size used for scaling calculations - lower value = larger icons */
        private const val BASE_SIZE = 6.5f
    }

    override fun paintIcon(c: Component?, g: Graphics, iconX: Int, iconY: Int) {
        val g2 = g as Graphics2D
        val config: GraphicsConfig = GraphicsUtil.setupAAPainting(g2)

        try {
            g2.color = color
            g2.stroke = BasicStroke(1.0f)

            // Calculate scale factor based on size
            val scale = size / BASE_SIZE

            // Draw bookmark flag shape (ribbon with V-notch at bottom)
            // Adjust positioning for better vertical centering with text
            val x = iconX + scale * 0.25f
            val y = iconY + scale * 0.4f
            val path = Path2D.Float()
            path.moveTo(x, y)
            path.lineTo(x + 3 * scale, y)
            path.lineTo(x + 3 * scale, y + 5 * scale)
            path.lineTo(x + 1.5 * scale, y + 3.5 * scale) // V-notch point
            path.lineTo(x, y + 5 * scale)
            path.lineTo(x, y)
            path.closePath()

            // Draw outline only (not filled) to match IntelliJ style
            if (filled) {
                g2.fill(path)
            } else {
                g2.draw(path)
            }
        } finally {
            config.restore()
        }
    }

    override fun getIconWidth(): Int {
        val scale = size / BASE_SIZE
        return (WIDTH_FACTOR * scale).toInt()
    }

    override fun getIconHeight() = size
}
