package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.components.JBLabel

fun htmlLabel(builder: (TextCanvas.() -> Unit)): JBLabel {
    val sb = StringBuilder()
    StringBuilderHtmlTextCanvas(sb).apply(builder)
    return JBLabel().apply {
        text = "<html>$sb</html>"
    }
}
