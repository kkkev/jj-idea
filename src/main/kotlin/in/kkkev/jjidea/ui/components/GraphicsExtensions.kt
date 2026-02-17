package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SimpleColoredComponent.getTextBaseLine
import com.intellij.ui.SimpleTextAttributes
import java.awt.Font
import java.awt.Graphics2D

/**
 * Draws the specified string centred vertically, given the specified total height.
 */
fun Graphics2D.drawStringCentredVertically(str: String, x: Int, height: Int) =
    this.drawString(str, x, getTextBaseLine(this.fontMetrics, height))

fun Font.deriveFont(style: SimpleTextAttributes): Font {
    val fontStyle = style.fontStyle
    return if (style.isSmaller) {
        deriveFont(fontStyle, size2D * 0.85f)
    } else {
        deriveFont(fontStyle)
    }
}
