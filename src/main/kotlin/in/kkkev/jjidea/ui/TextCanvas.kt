package `in`.kkkev.jjidea.ui

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.Shortenable
import kotlinx.datetime.Instant

interface TextCanvas {
    fun append(
        text: String,
        style: SimpleTextAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES
    )
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

fun TextCanvas.append(shortenable: Shortenable) {
    append(shortenable.short, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
    with(shortenable.displayRemainder) {
        if (isNotEmpty()) {
            append(this, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}

fun TextCanvas.append(qci: ChangeId) {
    append(qci.shortenable)

    qci.offset?.let {
        append(qci.optionalOffset, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
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
    append(user.name)
    append(" <")
    // TODO Make a mailto link
    append(user.email, SimpleTextAttributes.LINK_ATTRIBUTES)
    append(">")
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
