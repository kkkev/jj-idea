package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.io.URLUtil
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
import java.net.URLEncoder

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
    fun strikethrough(builder: TextCanvas.() -> Unit) = styled(SimpleTextAttributes.STYLE_STRIKEOUT, builder)

    fun colored(color: Color, builder: TextCanvas.() -> Unit)
    fun grey(builder: TextCanvas.() -> Unit) = colored(JBColor.GRAY, builder)

    fun linked(target: URI, builder: TextCanvas.() -> Unit)

    fun append(icon: IconSpec) = control("<icon src='${icon.qualified}'/>")

    /**
     * Append [icon] (optionally preceded by [prefixIcon], e.g. a conflict marker) immediately followed by [label],
     * with an optional trailing [suffix] in [suffixColor]. Implementations should render this as a single
     * unbreakable unit, so a bookmark/tag chip's icon is never separated from its name, and the name never wraps
     * mid-word, when the surrounding layout needs to wrap (jj-idea-kds1).
     */
    fun appendChip(
        icon: IconSpec,
        label: String,
        prefixIcon: IconSpec? = null,
        strikethrough: Boolean = false,
        suffix: String? = null,
        suffixColor: Color? = null
    ) {
        prefixIcon?.let { append(it) }
        append(icon)
        if (strikethrough) strikethrough { append(label) } else append(label)
        suffix?.let { if (suffixColor != null) colored(suffixColor) { append(it) } else append(it) }
    }

    fun truncate(builder: TextCanvas.() -> Unit) = builder()

    /**
     * Append [text] as a single unbreakable unit — the surrounding layout may still wrap before or after it, but
     * never split it mid-word. Use for short strings (e.g. a date/time) that read badly if broken across lines.
     */
    fun appendUnbreakable(text: String) = append(text)
}

abstract class StyledTextCanvas : TextCanvas {
    var style: SimpleTextAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES
        private set

    /** Color that propagates to icons. Set only by [colored], not by [foreground]. */
    private var iconColor: Color? = null

    protected fun surround(builder: TextCanvas.() -> Unit, deriver: SimpleTextAttributes.() -> SimpleTextAttributes) {
        val oldStyle = style
        style = style.deriver()
        this.builder()
        style = oldStyle
    }

    override fun styled(style: Int, builder: TextCanvas.() -> Unit) =
        surround(builder) { derive(this.style or style, null, null, null) }

    override fun colored(color: Color, builder: TextCanvas.() -> Unit) {
        val oldIconColor = iconColor
        iconColor = color
        surround(builder) { derive(this.style, color, null, null) }
        iconColor = oldIconColor
    }

    /**
     * Set the text foreground color without propagating to icons.
     *
     * Use this for layout-level foreground (e.g., table selection state) where
     * icons should keep their own semantic colors. Use [colored] when the color
     * is semantically meaningful and should apply to both text and icons.
     */
    open fun foreground(color: Color, builder: TextCanvas.() -> Unit) =
        surround(builder) { derive(this.style, color, null, null) }

    // TODO Actually build the link
    override fun linked(target: URI, builder: TextCanvas.() -> Unit) =
        surround(builder) { SimpleTextAttributes.merge(this, SimpleTextAttributes.LINK_ATTRIBUTES) }

    /** Apply the current [iconColor] to an icon that lacks an explicit [IconSpec.fillColor]. */
    protected fun applyCurrentColor(icon: IconSpec) =
        if (icon.fillColor == null && iconColor != null) icon.copy(fillColor = iconColor) else icon

    override fun append(icon: IconSpec) = super.append(applyCurrentColor(icon))
}

fun TextCanvas.append(message: JujutsuMessage) = append(JujutsuBundle.message(message.key))

fun TextCanvas.append(shortenable: ShortenableImpl) {
    bold { append(shortenable.short) }
    shortenable.displayRemainder.takeIf { it.isNotEmpty() }?.let { remainder -> smaller { grey { append(remainder) } } }
}

fun TextCanvas.append(changeId: ChangeId) {
    append(changeId.shortenable)
    changeId.offset?.let { smaller { bold { colored(JujutsuColors.DIVERGENT) { append(changeId.optionalOffset) } } } }
}

fun TextCanvas.append(changeKey: ChangeKey) {
    linked(URI("jjc://${URLUtil.encodePath(changeKey.repo.directory.path)}?${changeKey.revision}")) {
        with(changeKey.revision) {
            when (this) {
                is ChangeId -> append(this)
                is BookmarkName -> append(this)
                else -> append(this.toString())
            }
        }
    }
}

/**
 * Append [description], optionally linkifying issue-tracker references (e.g. `JIRA-123`) and bare URLs found by
 * [issueLinks] (an [IssueNavigationConfiguration], resolved from a `Project`) as clickable links (jj-idea-10fo).
 * When [issueLinks] is null (the default), or finds no matches, this renders exactly as before.
 */
fun TextCanvas.append(description: Description, issueLinks: IssueNavigationConfiguration? = null) {
    if (description.empty) {
        grey { italic { append(description.display) } }
    } else {
        appendLinkified(description.display, issueLinks)
    }
}

/** As [append], but only the first line of [description], for summary contexts. */
fun TextCanvas.appendSummary(description: Description, issueLinks: IssueNavigationConfiguration? = null) = truncate {
    if (description.empty) {
        grey { italic { append(description.summary) } }
    } else {
        appendLinkified(description.summary, issueLinks)
    }
}

/**
 * Split [text] into plain runs and issue-tracker/URL link runs found by [config], appending each accordingly.
 * Links are emitted via [TextCanvas.linked] (rather than raw HTML), so they carry over into any backend — including
 * [FragmentRecordingCanvas], where the URI becomes a [FragmentRecordingCanvas.Fragment.linkTarget] usable for
 * hit-testing (jj-idea-iesq) — not just the HTML details pane.
 */
private fun TextCanvas.appendLinkified(text: String, config: IssueNavigationConfiguration?) {
    val matches = config?.findIssueLinks(text).orEmpty()
    if (matches.isEmpty()) {
        append(text)
        return
    }
    IssueNavigationConfiguration.processTextWithLinks(
        text,
        matches,
        { plain -> append(plain) },
        { linkText, target ->
            runCatching { URI(target) }.getOrNull()
                ?.let { uri -> linked(uri) { append(linkText) } }
                ?: append(linkText)
        }
    )
}

fun TextCanvas.append(user: VcsUser) = user.email.takeIf { it.isNotEmpty() }
    ?.let { email -> linked(URI("mailto", email, null)) { append(user.name) } }
    ?: append(user.name)

/**
 * Renders a user as `Name <email>` for detailed views, as a single unbreakable mailto-linked unit — the surrounding
 * layout can wrap before or after it, but the name and email never split from each other or internally.
 */
fun TextCanvas.appendWithEmail(user: VcsUser) {
    val email = user.email.takeIf { it.isNotEmpty() }
    if (email != null) {
        linked(URI("mailto", email, null)) { appendUnbreakable("${user.name} <$email>") }
    } else {
        append(user.name)
    }
}

fun TextCanvas.append(instant: Instant) = append(DateTimeFormatter.formatRelative(instant))

/** Canonical `jjref://` URI identifying a specific clickable ref (bookmark or tag) on a log entry. */
fun refUri(entry: LogEntry, kind: String, name: String): URI =
    URI(
        "jjref://${URLUtil.encodePath(entry.repo.directory.path)}?${entry.id}" +
            "&kind=$kind&name=${URLEncoder.encode(name, "UTF-8")}"
    )

fun TextCanvas.append(name: BookmarkName) = colored(JujutsuColors.BOOKMARK) {
    smaller {
        appendChip(icon(JujutsuIcons::Bookmark), name.name)
    }
}

private fun TextCanvas.appendBookmarkChip(bookmark: Bookmark, label: String) = colored(JujutsuColors.BOOKMARK) {
    smaller {
        val iconRef = if (!bookmark.isRemote || bookmark.tracked) {
            JujutsuIcons::BookmarkTracked
        } else {
            JujutsuIcons::Bookmark
        }
        val divergence = buildString {
            if (bookmark.aheadCount > 0) append("↑${bookmark.aheadCount}")
            if (bookmark.behindCount > 0) append("↓${bookmark.behindCount}")
        }
        appendChip(
            icon(iconRef),
            label,
            prefixIcon = if (bookmark.conflict) icon(JujutsuIcons::Conflict) else null,
            strikethrough = bookmark.deleted,
            suffix = divergence.takeIf { it.isNotEmpty() },
            suffixColor = JujutsuColors.DIVERGENT
        )
    }
}

fun TextCanvas.append(bookmark: Bookmark) = appendBookmarkChip(bookmark, bookmark.name.name)

fun TextCanvas.append(group: BookmarkGroup) {
    group.local?.let { appendBookmarkChip(it, group.localName) }
    group.remotes.forEach { appendBookmarkChip(it, if (group.local != null) "@${it.remote}" else it.name.name) }
}

fun TextCanvas.append(repo: JujutsuRepository) {
    val color = RepositoryColors.getColor(repo)
    colored(color) {
        append(icon(JujutsuIcons::Repo))
        append(" ")
        bold { append(repo.displayName) }
    }
}

fun <T> TextCanvas.append(
    source: List<T>,
    separator: String = ", ",
    prefix: String = "",
    suffix: String = "",
    partBuilder: TextCanvas.(part: T) -> Unit
) {
    val map = source.map { { tc: TextCanvas -> tc.partBuilder(it) } }
    append(map, separator, prefix, suffix)
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

fun TextCanvas.appendChangeTooltip(detail: ChangeDetail) {
    append(detail.id)
    append(" (")
    append(detail.commitId)
    append(")\n")
    detail.author?.let { append(it) }
    detail.authorTimestamp?.let { ts ->
        if (detail.author != null) append(" \u00b7 ")
        append(DateTimeFormatter.formatAbsolute(ts))
    }
    control("<pre style='white-space: pre-wrap;'>") { appendSummary(detail.description) }
}

fun TextCanvas.appendSummary(entry: LogEntry) {
    append(ChangeKey(entry.repo, entry.id))
    append(" (")
    append(entry.commitId)
    append(")\n")

    appendBookmarks(entry, "\n")
    appendTags(entry, "\n")
}

/** Append the description summary and "(empty)" indicator for a log entry. */
fun TextCanvas.appendDescriptionAndEmptyIndicator(entry: LogEntry) {
    appendSummary(entry.description)
    if (entry.isEmpty) {
        grey {
            italic {
                append(" ")
                append(message("description.empty.suffix"))
            }
        }
    }
}

fun TextCanvas.appendBookmarks(entry: LogEntry, suffix: String = "") {
    val groups = entry.bookmarks.grouped()
    var first = true
    for (group in groups) {
        group.local?.let { local ->
            if (!first) append(" ")
            first = false
            linked(refUri(entry, "bookmark", local.name.name)) { appendBookmarkChip(local, group.localName) }
        }
        for (remote in group.remotes) {
            if (!first) append(" ")
            first = false
            val label = if (group.local != null) "@${remote.remote}" else remote.name.name
            linked(refUri(entry, "bookmark", remote.name.name)) { appendBookmarkChip(remote, label) }
        }
    }
    if (suffix.isNotEmpty()) append(suffix)
}

/**
 * A single bookmark or tag chip, paired with its underlying ref. Used by `cappedDecorations`
 * (jj-idea-w61m) to measure and selectively render decoration chips within a width budget,
 * collapsing the rest behind a "+N more" indicator.
 */
internal data class DecorationUnit(val ref: Any, val build: TextCanvas.() -> Unit)

/** One [DecorationUnit] per bookmark chip that [appendBookmarks] would render, in the same order. */
internal fun bookmarkDecorationUnits(entry: LogEntry): List<DecorationUnit> {
    val units = mutableListOf<DecorationUnit>()
    for (group in entry.bookmarks.grouped()) {
        group.local?.let { local ->
            units += DecorationUnit(local) {
                linked(refUri(entry, "bookmark", local.name.name)) { appendBookmarkChip(local, group.localName) }
            }
        }
        for (remote in group.remotes) {
            val label = if (group.local != null) "@${remote.remote}" else remote.name.name
            units += DecorationUnit(remote) {
                linked(refUri(entry, "bookmark", remote.name.name)) { appendBookmarkChip(remote, label) }
            }
        }
    }
    return units
}

/** One [DecorationUnit] per tag chip that [appendTags] would render, in the same order. */
internal fun tagDecorationUnits(entry: LogEntry): List<DecorationUnit> =
    entry.tags.map { tag -> DecorationUnit(tag) { linked(refUri(entry, "tag", tag.name)) { append(tag) } } }

fun TextCanvas.append(tag: Tag) = colored(JujutsuColors.TAG) {
    smaller {
        appendChip(icon(JujutsuIcons::Tag), tag.name)
    }
}

fun TextCanvas.appendTags(entry: LogEntry, suffix: String = "") {
    var first = true
    for (tag in entry.tags) {
        if (!first) append(" ")
        first = false
        linked(refUri(entry, "tag", tag.name)) { append(tag) }
    }
    if (entry.tags.isNotEmpty() && suffix.isNotEmpty()) append(suffix)
}

fun TextCanvas.appendParents(entry: LogEntry) = smaller {
    if (entry.parentIds.isNotEmpty()) {
        append(message("details.parents.label"))
        append(entry.parentIds.map { ChangeKey(entry.repo, it) }, partBuilder = TextCanvas::append, prefix = " ")
    } else {
        append(message("details.parents.none"))
    }
}

fun TextCanvas.append(item: RefItem) = when (item) {
    is BookmarkItem -> append(item.bookmark)
    is TagItem -> append(item.tag)
}

fun TextCanvas.append(choice: RevisionChoice, entries: List<LogEntry> = emptyList()) {
    when (choice) {
        is RevisionChoice.Ref -> {
            append(choice.item)
            val entry = choice.item.id?.let { id -> entries.find { it.id == id } }
                ?: (choice.item as? TagItem)?.let { t -> entries.find { e -> e.tags.any { it.name == t.tag.name } } }
            if (entry != null) {
                append(" ")
                appendSummary(entry.description)
            }
        }
        is RevisionChoice.Change -> {
            append(icon(AllIcons.Vcs::CommitNode))
            append(choice.id)
            append(" ")
            appendSummary(choice.description)
        }
        is RevisionChoice.FreeForm -> grey {
            append(icon(AllIcons.Actions::Search))
            append(" ")
            append(JujutsuBundle.message("dialog.revisionselector.freeform", choice.text))
        }
    }
}

fun TextCanvas.appendConflict(entry: LogEntry) {
    if (entry.hasConflict) {
        colored(JujutsuColors.CONFLICT) {
            append(icon(JujutsuIcons::Conflict))
        }
    }
}

fun TextCanvas.appendSummaryAndStatuses(entry: LogEntry) {
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
                append(icon(JujutsuIcons::Conflict))
                append(" ")
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
            append(icon(JujutsuIcons::Immutable))
            append(" ")
            append(message("status.immutable"))
        }
    }
    append(statusParts, prefix = " [", suffix = "]\n")
}
