package `in`.kkkev.jjidea.ui

import com.intellij.codeInsight.codeVision.ui.model.richText.RichText
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Description
import kotlinx.datetime.Instant

interface TextCanvas {
    fun append(
        text: String,
        style: SimpleTextAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES
    )
}

class HtmlTextCanvas(val richText: RichText = RichText()) : TextCanvas {
    override fun append(text: String, style: SimpleTextAttributes) = richText.append(text, style)
}

class StringBuilderHtmlTextCanvas(val sb: StringBuilder) : TextCanvas {
    override fun append(text: String, style: SimpleTextAttributes) {
        val canvas = HtmlTextCanvas()
        canvas.append(text, style)
        sb.append(canvas.richText.toString())
    }
}

fun htmlText(builder: (TextCanvas.() -> Unit)): CharSequence {
    val sb = StringBuilder()
    val canvas = StringBuilderHtmlTextCanvas(sb)
    builder.invoke(canvas)
    return sb
}

class ComponentTextCanvas(val component: SimpleColoredComponent) : TextCanvas {
    override fun append(text: String, style: SimpleTextAttributes) = component.append(text, style)
}

fun TextCanvas.append(changeId: ChangeId) {
    append(changeId.short, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
    with(changeId.displayRemainder) {
        if (isNotEmpty()) {
            append(this, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}

fun TextCanvas.append(description: Description) {
    val style = if (description.empty) {
        SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
    } else {
        SimpleTextAttributes.REGULAR_ATTRIBUTES
    }
    append(description.display, style)
}

fun TextCanvas.appendSummary(description: Description) {
    val style = if (description.empty) {
        SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
    } else {
        SimpleTextAttributes.REGULAR_ATTRIBUTES
    }
    append(description.summary, style)
}

fun TextCanvas.append(user: VcsUser) {
    append("${user.name} &lt;")
    append("user.email", SimpleTextAttributes.LINK_ATTRIBUTES)
    append("&lt;")
}

fun TextCanvas.append(instant: Instant) {
    append(DateTimeFormatter.formatRelative(instant))
}

fun TextCanvas.append(bookmarks: List<Bookmark>) {
    if (bookmarks.isNotEmpty()) {
        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        bookmarks.forEach { bookmark ->
            append(bookmark.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }
}
