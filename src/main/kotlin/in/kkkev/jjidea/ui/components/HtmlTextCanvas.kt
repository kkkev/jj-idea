package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.SimpleTextAttributes
import java.awt.Color
import java.awt.Font
import java.net.URI

/**
 * Create a full HTML document including wrapping `<html>` tag.
 */
fun htmlString(builder: (TextCanvas.() -> Unit)) = htmlText { control("<html>", "</html>", builder) }

/**
 * Create some inline HTML text, to be used inside an existing HTML document.
 */
private fun htmlText(builder: (TextCanvas.() -> Unit)) = HtmlTextCanvas(StringBuilder()).apply(builder).sb.toString()

private class HtmlTextCanvas(val sb: StringBuilder) : StyledTextCanvas() {
    override fun control(open: String, close: String, builder: TextCanvas.() -> Unit) {
        sb.append(open)
        builder()
        sb.append(close)
    }

    override fun append(text: String) {
        sb.append(Formatters.escapeHtml(text))
    }

    // TODO Optimise nested styles so that they collapse into one if they start and end at the same point
    override fun styled(style: Int, builder: TextCanvas.() -> Unit) {
        val bold = (style and Font.BOLD) != 0
        val italic = (style and Font.ITALIC) != 0
        val smaller = (style and SimpleTextAttributes.STYLE_SMALLER) != 0

        if (bold) sb.append("<b>")
        if (italic) sb.append("<i>")
        if (smaller) sb.append("<span style='font-size: 85%'>")
        builder(this)
        if (smaller) sb.append("</span>")
        if (italic) sb.append("</i>")
        if (bold) sb.append("</b>")
    }

    override fun colored(color: Color, builder: TextCanvas.() -> Unit) {
        val colored = color != style.fgColor
        if (colored) sb.append("<span style='color: ${color.rgbString}'>")
        builder(this)
        if (colored) sb.append("</span>")
    }

    override fun linked(target: URI, builder: TextCanvas.() -> Unit) {
        sb.append("<a href='$target'>")
        builder(this)
        sb.append("</a>")
    }

    private val Color.rgbString get() = "rgb($red,$green,$blue);"
}
