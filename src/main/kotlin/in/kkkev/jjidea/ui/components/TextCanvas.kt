package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.JujutsuMessage
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.message
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.log.RepositoryColors
import kotlinx.datetime.Instant
import java.awt.Color
import java.awt.Font
import java.net.URI

/**
 * A canvas onto which to paint styled text and icons. Functions provide a DSL that applies styling to embedded content,
 * allowing text to be declared in styled sections, e.g.
 * ```
 * bold {
 *     append("Here is some bold text ")
 *     italic { append("and this is bold and italic ") }
 *     append("back to just bold")
 * }
 * ```
 */
interface TextCanvas {
    /**
     * Append the specified text to the canvas. This is intended specifically for displaying that text; therefore text
     * applied here is escaped before adding to HTML, for example.
     */
    fun append(text: String)

    /**
     * Apply context-specific controls to the canvas. For HTML, this is equivalent to surrounding with an HTML tag.
     * Strings are applied to the stream verbatim; crucially, they are not escaped, so this is the way to provide
     * further control on the HTML output.
     */
    fun control(open: String, close: String = "", builder: TextCanvas.() -> Unit = {}) = builder()

    /**
     * Apply the specified font-styling to content in the provided builder. Font-styling is additive; for example, if
     * the canvas is already italic, and bold is applied, then content is both bold and italic.
     */
    fun styled(style: Int, builder: TextCanvas.() -> Unit)
    fun bold(builder: TextCanvas.() -> Unit) = styled(Font.BOLD, builder)
    fun italic(builder: TextCanvas.() -> Unit) = styled(Font.ITALIC, builder)

    fun smaller(builder: TextCanvas.() -> Unit) = styled(SimpleTextAttributes.STYLE_SMALLER, builder)

    fun colored(color: Color, builder: TextCanvas.() -> Unit)
    fun grey(builder: TextCanvas.() -> Unit) = colored(JBColor.GRAY, builder)

    fun linked(target: URI, builder: TextCanvas.() -> Unit)

    fun append(icon: IconSpec) = control("<icon src='${icon.qualified}'/>")

    fun truncate(builder: TextCanvas.() -> Unit) = builder()
}

abstract class StyledTextCanvas : TextCanvas {
    var style: SimpleTextAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES
        private set

    private fun surround(builder: TextCanvas.() -> Unit, deriver: SimpleTextAttributes.() -> SimpleTextAttributes) {
        val oldStyle = style
        style = style.deriver()
        this.builder()
        style = oldStyle
    }

    override fun styled(style: Int, builder: TextCanvas.() -> Unit) =
        surround(builder) { derive(style, null, null, null) }

    override fun colored(color: Color, builder: TextCanvas.() -> Unit) =
        surround(builder) { derive(0, color, null, null) }

    // TODO Actually build the link
    override fun linked(target: URI, builder: TextCanvas.() -> Unit) =
        surround(builder) { SimpleTextAttributes.merge(this, SimpleTextAttributes.LINK_ATTRIBUTES) }
}

class ComponentTextCanvas(val component: SimpleColoredComponent) : StyledTextCanvas() {
    override fun append(text: String) = component.append(text, style)
}

fun TextCanvas.append(message: JujutsuMessage) = append(JujutsuBundle.message(message.key))

fun TextCanvas.append(shortenable: Shortenable) {
    bold { append(shortenable.short) }
    shortenable.displayRemainder.takeIf { it.isNotEmpty() }?.let { remainder -> smaller { grey { append(remainder) } } }
}

fun TextCanvas.append(changeId: ChangeId) {
    append(changeId.shortenable)
    changeId.offset?.let { smaller { bold { colored(JujutsuColors.DIVERGENT) { append(changeId.optionalOffset) } } } }
}

fun TextCanvas.append(description: Description) {
    if (description.empty) {
        grey { italic { append(description.display) } }
    } else {
        append(description.display)
    }
}

fun TextCanvas.appendSummary(description: Description) = truncate {
    if (description.empty) {
        grey { italic { append(description.summary) } }
    } else {
        append(description.summary)
    }
}

fun TextCanvas.append(user: VcsUser) = user.email.takeIf { it.isNotEmpty() }
    ?.let { email -> linked(URI("mailto", email, null)) { append(user.name) } }
    ?: append(user.name)

fun TextCanvas.append(instant: Instant) = append(DateTimeFormatter.formatRelative(instant))

fun TextCanvas.append(bookmark: Bookmark) {
    colored(JujutsuColors.BOOKMARK) {
        append(icon(AllIcons.Nodes::Bookmark, JujutsuColors.BOOKMARK))
        smaller { append(bookmark.name) }
    }
}

fun TextCanvas.append(repo: JujutsuRepository) {
    val color = RepositoryColors.getColor(repo)
    colored(color) {
        append(icon(AllIcons.Nodes::Folder, color))
        bold { append(repo.displayName) }
    }
}

fun TextCanvas.append(
    parts: List<TextCanvas.() -> Unit>,
    separator: String = ", ",
    prefix: String = "",
    suffix: String = ""
) {
    if (parts.isNotEmpty()) {
        parts.forEachIndexed { i, part ->
            append(if (i > 0) separator else prefix)
            part()
        }
        append(suffix)
    }
}

fun TextCanvas.appendSummary(entry: LogEntry) {
    append(entry.id)
    append(" (")
    append(entry.commitId)
    append(")\n")

    appendBookmarks(entry, "\n")
}

fun TextCanvas.appendBookmarks(entry: LogEntry, suffix: String = "") =
    append(entry.bookmarks.map { { append(it) } }, separator = " ", prefix = " ", suffix = suffix)

fun TextCanvas.appendParents(entry: LogEntry) = smaller {
    if (entry.parentIds.isNotEmpty()) {
        append(message("details.parents.label"))
        append(entry.parentIds.map { { append(it) } })
    } else {
        append(message("details.parents.none"))
    }
}

fun TextCanvas.appendConflict(entry: LogEntry, suffix: TextCanvas.() -> Unit = {}) = {
    if (entry.hasConflict) {
        colored(JujutsuColors.CONFLICT) {
            append(icon(JujutsuIcons::Conflict, JujutsuColors.CONFLICT))
            suffix()
        }
    }
}

fun TextCanvas.appendSummaryAndStatuses(entry: LogEntry) {
    control("<p style='margin: 0; padding-bottom: 4px; white-space: nowrap;'>", "</p>") {
        append(entry.repo)
        append("\n")

        appendSummary(entry)

        val statusParts = mutableListOf<TextCanvas.() -> Unit>()
        if (entry.isWorkingCopy) {
            statusParts.add {
                colored(JujutsuColors.WORKING_COPY) {
                    append("@ ")
                    append(message("status.workingcopy"))
                }
            }
        }
        if (entry.hasConflict) {
            statusParts.add {
                colored(JujutsuColors.CONFLICT) {
                    append(icon(JujutsuIcons::Conflict, JujutsuColors.CONFLICT))
                    append(message("status.conflict"))
                }
            }
        }
        if (entry.isEmpty) {
            statusParts.add { append(message("status.empty")) }
        }
        if (entry.isDivergent) {
            statusParts.add { colored(JujutsuColors.DIVERGENT) { append(message("status.divergent")) } }
        }
        if (entry.immutable) {
            statusParts.add {
                append(icon(AllIcons.Nodes::Private))
                append(message("status.immutable"))
            }
        }
        append(statusParts, prefix = " [", suffix = "]")
    }
}
