package `in`.kkkev.jjidea.ui.log

import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import java.net.URI
import java.net.URLDecoder

sealed interface LogClickTarget {
    val repo: JujutsuRepository
    val entry: LogEntry

    companion object {
        private val REF_URL_PARSER = Regex("^jjref://([^?]+)\\?([^&]+)&kind=([^&]+)&name=(.+)$")

        /**
         * Resolve a [uri] to a [LogClickTarget] using the given [entry]'s ref lists: either a
         * `jjref://` bookmark/tag chip, or an `http(s)://` issue-tracker link linkified inside the
         * description or a chip label by [in.kkkev.jjidea.ui.components.appendLinkified]
         * (jj-idea-91qf, jj-idea-vrmv).
         */
        fun resolve(uri: URI, entry: LogEntry): LogClickTarget? {
            if (uri.scheme == "http" || uri.scheme == "https") return IssueLinkClick(entry.repo, entry, uri)
            val m = REF_URL_PARSER.matchEntire(uri.toString()) ?: return null
            val kind = m.groupValues[3]
            val name = URLDecoder.decode(m.groupValues[4], "UTF-8")
            return when (kind) {
                "bookmark" -> {
                    val bookmark = entry.bookmarks.find { it.name.name == name } ?: return null
                    BookmarkClick(entry.repo, entry, bookmark)
                }
                "tag" -> {
                    val tag = entry.tags.find { it.name == name } ?: return null
                    TagClick(entry.repo, entry, tag)
                }
                else -> null
            }
        }
    }
}

data class BookmarkClick(
    override val repo: JujutsuRepository,
    override val entry: LogEntry,
    val bookmark: Bookmark
) : LogClickTarget

data class TagClick(
    override val repo: JujutsuRepository,
    override val entry: LogEntry,
    val tag: Tag
) : LogClickTarget

/**
 * The author or committer name column was clicked (jj-idea-iesq). [canFilter] is true only for
 * the author column — the author filter matches on `LogEntry.author.email`, so filtering by a
 * committer's email would silently never match.
 */
data class PersonClick(
    override val repo: JujutsuRepository,
    override val entry: LogEntry,
    val user: VcsUser,
    val canFilter: Boolean
) : LogClickTarget

/**
 * The "+N more" overflow chip was clicked (jj-idea-w61m): [hidden] holds the bookmark/tag click
 * targets that were collapsed out of the capped decoration display, for showing in a popup.
 * Resolved directly in `JujutsuLogTable.clickTargetAt` (via `cappedDecorations`), not through
 * [resolve], since the hidden list isn't recoverable from the URI alone.
 */
data class MoreRefsClick(
    override val repo: JujutsuRepository,
    override val entry: LogEntry,
    val hidden: List<LogClickTarget>
) : LogClickTarget

/**
 * An issue-tracker reference (e.g. `JIRA-123`) linkified by
 * [in.kkkev.jjidea.ui.components.appendLinkified] was clicked - in the description
 * (jj-idea-91qf) or inside a bookmark/tag chip label (jj-idea-vrmv). [uri] is the tracker URL
 * built from the matching [com.intellij.openapi.vcs.IssueNavigationConfiguration] link.
 */
data class IssueLinkClick(
    override val repo: JujutsuRepository,
    override val entry: LogEntry,
    val uri: URI
) : LogClickTarget
