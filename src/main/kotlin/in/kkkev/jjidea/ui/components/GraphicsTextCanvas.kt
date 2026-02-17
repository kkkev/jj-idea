package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SimpleTextAttributes
import java.awt.Graphics2D
import java.awt.Point
import kotlin.math.roundToInt

class GraphicsTextCanvas(val g2d: Graphics2D, startX: Int, baseline: Int) : TextCanvas {
    val baseFont = g2d.font
    val baseColor = g2d.color
    val cursor = Point(startX, baseline)

    override fun append(text: String, style: SimpleTextAttributes) {
        g2d.font = baseFont.deriveFont(style)
        g2d.color = style.fgColor
        g2d.drawString(text, cursor.x, cursor.y)
        val width = g2d.fontMetrics.getStringBounds(text, g2d).width
        cursor.translate((width + 0.5f).roundToInt(), 0)
        g2d.color = baseColor
        g2d.font = baseFont
    }
}
