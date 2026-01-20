package `in`.kkkev.jjidea.ui

import com.intellij.ui.SimpleColoredComponent.getTextBaseLine
import java.awt.Graphics2D

/**
 * Draws the specified string centred vertically, given the specified total height.
 */
fun Graphics2D.drawStringCentredVertically(str: String, x: Int, height: Int) =
    this.drawString(str, x, getTextBaseLine(this.fontMetrics, height))
