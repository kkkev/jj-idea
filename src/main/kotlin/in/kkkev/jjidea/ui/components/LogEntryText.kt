package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.util.io.URLUtil
import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.JujutsuMessage
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.message
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.log.RepositoryColors
import java.net.URI
import java.net.URLEncoder
import kotlin.time.Instant

/**
 * The jj-domain rendering vocabulary built on the generic [TextCanvas] DSL - `append(Bookmark)`,
 * `appendSummaryAndStatuses`, ref chips, etc. Split from `TextCanvas.kt` so the generic canvas
 * mechanism isn't coupled to `jj`/`JujutsuRepository` types.
 */
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
 * Append [description], linkifying issue-tracker references and bare URLs via [TextCanvas.linkifier]
 * as clickable links (jj-idea-10fo) - see [appendLinkified] and [underlining] for hover.
 */
fun TextCanvas.append(description: Description) {
    if (description.empty) {
        grey { italic { append(description.display) } }
    } else {
        appendLinkified(description.display)
    }
}

/** As [append], but only the first line of [description], for summary contexts. */
fun TextCanvas.appendSummary(description: Description) = truncate {
    if (description.empty) {
        grey { italic { append(description.summary) } }
    } else {
        appendLinkified(description.summary)
    }
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

/**
 * Canonical `jjref://` URI identifying a specific ref (bookmark or tag) on a log entry. Wrapping a
 * chip in [linked] with this URI makes it resolvable by right-click
 * ([in.kkkev.jjidea.ui.log.LogClickTarget.resolve]) - bookmark/tag chips have no left-click action
 * or hover cue of their own (jj-idea-wkcz), only the right-click context menu.
 */
fun refUri(entry: LogEntry, kind: String, name: String): URI =
    URI(
        "jjref://${URLUtil.encodePath(entry.repo.directory.path)}?${entry.id}" +
            "&kind=$kind&name=${URLEncoder.encode(name, "UTF-8")}"
    )

fun TextCanvas.append(name: BookmarkName) = colored(JujutsuColors.BOOKMARK) {
    smaller {
        appendUnbreakable {
            append(icon(JujutsuIcons::Bookmark))
            appendLinkified(name.name)
        }
    }
}

private fun TextCanvas.appendBookmarkChip(bookmark: Bookmark, label: String) = colored(JujutsuColors.BOOKMARK) {
    smaller {
        // Precedence: a conflicted bookmark always shows the conflict glyph, even if it's also pending
        // deletion (still conveyed by strikethrough); otherwise deleted beats tracked/plain.
        val iconRef = when {
            bookmark.conflict -> JujutsuIcons::BookmarkConflict
            bookmark.deleted -> JujutsuIcons::BookmarkDeleted
            !bookmark.isRemote || bookmark.tracked -> JujutsuIcons::BookmarkTracked
            else -> JujutsuIcons::Bookmark
        }
        val divergence = buildString {
            if (bookmark.aheadCount > 0) append("↑${bookmark.aheadCount}")
            if (bookmark.behindCount > 0) append("↓${bookmark.behindCount}")
        }
        appendUnbreakable {
            append(icon(iconRef))
            if (bookmark.deleted) strikethrough { appendLinkified(label) } else appendLinkified(label)
            if (divergence.isNotEmpty()) colored(JujutsuColors.DIVERGENT) { append(divergence) }
        }
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

/**
 * Append the description summary and "(empty)" indicator for a log entry, linkifying via
 * [TextCanvas.linkifier] (jj-idea-91qf) - see [appendLinkified].
 */
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

/**
 * Wrap [build] (a chip) in a [linked] `jjref://` ref for [entry]'s [kind]/[name] - the one place
 * that pairing is built, shared by [appendBookmarks]/[appendTags] and their [RefChip]-producing
 * counterparts below, so a bookmark/tag chip and a plain hyperlink aren't built two different ways.
 */
private fun TextCanvas.refChip(entry: LogEntry, kind: String, name: String, build: TextCanvas.() -> Unit) =
    linked(refUri(entry, kind, name), build)

/**
 * Append every bookmark chip for [entry]. [TextCanvas.linkifier] linkifies any issue-tracker
 * reference within a bookmark's own name (e.g. `jira-123-fix-thing`) (jj-idea-vrmv) - see
 * [appendBookmarkChip].
 */
fun TextCanvas.appendBookmarks(entry: LogEntry, suffix: String = "") {
    val groups = entry.bookmarks.grouped()
    var first = true
    for (group in groups) {
        group.local?.let { local ->
            if (!first) append(" ")
            first = false
            refChip(entry, "bookmark", local.name.name) { appendBookmarkChip(local, group.localName) }
        }
        for (remote in group.remotes) {
            if (!first) append(" ")
            first = false
            val label = if (group.local != null) "@${remote.remote}" else remote.name.name
            refChip(entry, "bookmark", remote.name.name) { appendBookmarkChip(remote, label) }
        }
    }
    if (suffix.isNotEmpty()) append(suffix)
}

/**
 * A single bookmark or tag chip, paired with its underlying ref. Used by `cappedDecorations`
 * (jj-idea-w61m) to measure and selectively render decoration chips within a width budget,
 * collapsing the rest behind a "+N more" indicator.
 */
internal data class RefChip(val ref: Any, val build: TextCanvas.() -> Unit)

/** One [RefChip] per bookmark chip that [appendBookmarks] would render, in the same order. */
internal fun bookmarkRefChips(entry: LogEntry): List<RefChip> {
    val units = mutableListOf<RefChip>()
    for (group in entry.bookmarks.grouped()) {
        group.local?.let { local ->
            units += RefChip(local) {
                refChip(entry, "bookmark", local.name.name) { appendBookmarkChip(local, group.localName) }
            }
        }
        for (remote in group.remotes) {
            val label = if (group.local != null) "@${remote.remote}" else remote.name.name
            units += RefChip(remote) {
                refChip(entry, "bookmark", remote.name.name) { appendBookmarkChip(remote, label) }
            }
        }
    }
    return units
}

/** One [RefChip] per tag chip that [appendTags] would render, in the same order. */
internal fun tagRefChips(entry: LogEntry): List<RefChip> =
    entry.tags.map { tag -> RefChip(tag) { refChip(entry, "tag", tag.name) { append(tag) } } }

/**
 * [TextCanvas.linkifier] linkifies any issue-tracker reference within the tag's own name
 * (jj-idea-vrmv) - see [appendUnbreakable].
 */
fun TextCanvas.append(tag: Tag) = colored(JujutsuColors.TAG) {
    smaller {
        appendUnbreakable {
            append(icon(JujutsuIcons::Tag))
            appendLinkified(tag.name)
        }
    }
}

fun TextCanvas.appendTags(entry: LogEntry, suffix: String = "") {
    var first = true
    for (tag in entry.tags) {
        if (!first) append(" ")
        first = false
        refChip(entry, "tag", tag.name) { append(tag) }
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
