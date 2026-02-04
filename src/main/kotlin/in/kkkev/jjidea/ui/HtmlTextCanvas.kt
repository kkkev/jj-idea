package `in`.kkkev.jjidea.ui

import com.intellij.codeInsight.codeVision.ui.model.richText.RichText
import com.intellij.ui.SimpleTextAttributes

class HtmlTextCanvas(val richText: RichText = RichText()) : TextCanvas {
    override fun append(text: String, style: SimpleTextAttributes) = richText.append(text, style)
}
