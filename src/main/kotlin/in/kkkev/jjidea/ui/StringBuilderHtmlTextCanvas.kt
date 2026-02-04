package `in`.kkkev.jjidea.ui

import com.intellij.ui.SimpleTextAttributes

class StringBuilderHtmlTextCanvas(val sb: StringBuilder) : TextCanvas {
    override fun append(text: String, style: SimpleTextAttributes) {
        val canvas = HtmlTextCanvas()
        canvas.append(text, style)
        sb.append(canvas.richText.toString())
    }
}
