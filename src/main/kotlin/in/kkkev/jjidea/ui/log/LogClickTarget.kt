package `in`.kkkev.jjidea.ui.log

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.util.io.URLUtil
import com.intellij.vcs.log.VcsUser
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import java.net.URI
import java.net.URLDecoder

/**
 * Something a link inside jj-idea's rendered content resolves to - the log table's fragment-based
 * columns, or an HTML pane like the commit details panel. [resolve] is the single place that
 * interprets every link scheme jj-idea emits (`jjref://`, `jjc://`, `mailto:`, issue-tracker
 * `http(s)://`); [hasHoverCue] and [displayName] say how to present a resolved target;
 * [performDefaultAction] is what left-click does, and
 * [in.kkkev.jjidea.ui.log.JujutsuLogContextMenuActions.clickActionGroup] is what right-click shows.
 * Adding a new link kind means adding one variant plus one branch in each of these - not hunting
 * through every renderer that might show a link (this file used to be just [resolve], covering
 * only `jjref`/`http(s)`, while `jjc` had its own regex dispatcher in `IconAwareHtmlPane` and
 * `mailto` its own lookup in `JujutsuCommitDetailsPanel` - three separate, overlapping seams).
 */
sealed interface LogClickTarget {
    companion object {
        private val REF_URL_PARSER = Regex("^jjref://([^?]+)\\?([^&]+)&kind=([^&]+)&name=(.+)$")
        private val CHANGE_URL_PARSER = Regex("^jjc://([^?]+)\\?(.+)$")

        /**
         * Resolve [uri] to a [LogClickTarget]. `jjref://` (a bookmark/tag chip, or its `kind=overflow`
         * "+N more" sentinel - handled by the caller, see [MoreRefsClick]) and `mailto:` (an
         * author/committer) are looked up against whichever of [entries] they name; `jjc://` (change-ID
         * navigation) resolves its repository from the URI's own embedded path via [project], needing
         * no entry lookup; `http(s)://` (an issue-tracker reference linkified by
         * [in.kkkev.jjidea.ui.components.appendLinkified]) needs neither - the target URL is
         * self-sufficient. Used identically by the interactive log table (usually a single-element
         * [entries] - the row being hit-tested) and the HTML details pane (every currently-displayed
         * commit, since a `jjref`/`mailto` link there isn't necessarily about the first one). [project]
         * is nullable since `jjc://` links never actually occur in the log table's fragment-based
         * rendering (only in HTML surfaces) - callers without one on hand simply can't resolve one.
         */
        fun resolve(uri: URI, project: Project?, entries: List<LogEntry>): LogClickTarget? = when (uri.scheme) {
            "http", "https" -> IssueLinkClick(uri)
            "mailto" -> personClickForEmail(uri.schemeSpecificPart, entries)
            "jjc" -> project?.let { resolveChangeNavigation(uri, it) }
            "jjref" -> resolveRef(uri, entries)
            else -> null
        }

        private fun resolveChangeNavigation(uri: URI, project: Project): ChangeNavigationClick? {
            val m = CHANGE_URL_PARSER.matchEntire(uri.toString()) ?: return null
            val path = URLUtil.unescapePercentSequences(m.groupValues[1])
            val repo = project.possibleJujutsuRepositoryFor(VcsUtil.getFilePath(path, true)) ?: return null
            return ChangeNavigationClick(repo, ChangeKey(repo, ChangeId(m.groupValues[2])))
        }

        private fun resolveRef(uri: URI, entries: List<LogEntry>): LogClickTarget? {
            val m = REF_URL_PARSER.matchEntire(uri.toString()) ?: return null
            val entry = entries.find { it.id == ChangeId(m.groupValues[2]) } ?: return null
            val kind = m.groupValues[3]
            val name = URLDecoder.decode(m.groupValues[4], "UTF-8")
            return when (kind) {
                "bookmark" -> entry.bookmarks.find { it.name.name == name }
                    ?.let { BookmarkClick(entry.repo, entry, it) }
                "tag" -> entry.tags.find { it.name == name }?.let { TagClick(entry.repo, entry, it) }
                else -> null
            }
        }

        /**
         * Find the [PersonClick] matching [email] among [entries]' authors/committers - a `mailto:`
         * href only carries the email, not which entry or role (author vs. committer) it came from, so
         * this recovers both by matching. Author matches take priority over committer matches
         * (mirroring [JujutsuLogTableRenderers.findPersonClickTarget]'s per-column precedence), since
         * `canFilter` is only meaningful for the author role.
         */
        private fun personClickForEmail(email: String, entries: List<LogEntry>): PersonClick? {
            entries.firstNotNullOfOrNull { entry ->
                entry.author?.takeIf { it.email == email }?.let { PersonClick(entry.repo, entry, it, canFilter = true) }
            }?.let { return it }
            return entries.firstNotNullOfOrNull { entry ->
                entry.committer?.takeIf { it.email == email }
                    ?.let { PersonClick(entry.repo, entry, it, canFilter = false) }
            }
        }
    }
}

data class BookmarkClick(val repo: JujutsuRepository, val entry: LogEntry, val bookmark: Bookmark) : LogClickTarget

data class TagClick(val repo: JujutsuRepository, val entry: LogEntry, val tag: Tag) : LogClickTarget

/**
 * The author or committer name column was clicked (jj-idea-iesq). [canFilter] is true only for
 * the author column — the author filter matches on `LogEntry.author.email`, so filtering by a
 * committer's email would silently never match.
 */
data class PersonClick(
    val repo: JujutsuRepository,
    val entry: LogEntry,
    val user: VcsUser,
    val canFilter: Boolean
) : LogClickTarget

/**
 * The "+N more" overflow chip was clicked (jj-idea-w61m): [hidden] holds the bookmark/tag click
 * targets that were collapsed out of the capped decoration display, for showing in a popup.
 * Resolved directly in `JujutsuLogTable.clickTargetAt` (via `cappedDecorations`), not through
 * [LogClickTarget.resolve], since the hidden list isn't recoverable from the URI alone. Its real
 * left-click action (showing that popup) needs the click's screen coordinates, which
 * [performDefaultAction]'s signature doesn't carry - callers special-case this variant before
 * calling it generically, exactly as they already did before this type existed.
 */
data class MoreRefsClick(val repo: JujutsuRepository, val entry: LogEntry, val hidden: List<LogClickTarget>) :
    LogClickTarget

/**
 * An issue-tracker reference (e.g. `JIRA-123`) linkified by
 * [in.kkkev.jjidea.ui.components.appendLinkified] was clicked - in the description (jj-idea-91qf)
 * or inside a bookmark/tag chip label (jj-idea-vrmv). [uri] is the tracker URL built from the
 * matching [com.intellij.openapi.vcs.IssueNavigationConfiguration] link. Carries no `repo`/`entry`
 * unlike the other variants - the tracker URL is self-sufficient, and in a multi-commit view (the
 * details pane) there'd be no reliable way to attribute it to exactly one of them anyway.
 */
data class IssueLinkClick(val uri: URI) : LogClickTarget

/**
 * A `jjc://` change-ID link (emitted by [in.kkkev.jjidea.ui.components.append] for a [ChangeKey])
 * was clicked - navigates the log/working-copy selection to [changeKey]. Unlike `jjref`/`http(s)`
 * links, this has no natural owning [LogEntry] in a multi-commit view (the change it points to,
 * e.g. a parent commit, need not be among the currently-displayed entries), so it carries only the
 * [repo] resolved from the URI's own embedded path.
 */
data class ChangeNavigationClick(val repo: JujutsuRepository, val changeKey: ChangeKey) : LogClickTarget

/**
 * Whether [this] shows a hand cursor / underlines on hover (jj-idea-iesq). Bookmark/tag chips have
 * no left-click action (jj-idea-wkcz) - only their right-click menu - so hinting "clickable" here
 * would be misleading.
 */
val LogClickTarget.hasHoverCue: Boolean
    get() = this !is BookmarkClick && this !is TagClick

/** A short human-readable label for [this], e.g. for [JujutsuLogTable.showMoreRefsPopup]'s per-item submenu title. */
val LogClickTarget.displayName: String
    get() = when (this) {
        is BookmarkClick -> bookmark.name.name
        is TagClick -> tag.name
        is PersonClick -> user.name
        is IssueLinkClick -> uri.toString()
        is ChangeNavigationClick -> changeKey.revision.toString()
        is MoreRefsClick -> "+${hidden.size} more"
    }

/**
 * Perform [this] target's left-click default action against [project], if it has one - opening a
 * `mailto:`/issue-tracker link in the browser, or navigating to a [ChangeNavigationClick]'s change.
 * Bookmark/tag chips have no left-click action (jj-idea-wkcz); [MoreRefsClick]'s real action (a
 * popup) needs click coordinates this signature doesn't have, so callers special-case it first -
 * see [MoreRefsClick]'s doc.
 */
fun LogClickTarget.performDefaultAction(project: Project) {
    when (this) {
        is PersonClick -> BrowserUtil.browse(URI("mailto", user.email, null))
        is IssueLinkClick -> BrowserUtil.browse(uri)
        is ChangeNavigationClick -> project.stateModel.changeSelection.notify(changeKey)
        is BookmarkClick, is TagClick, is MoreRefsClick -> Unit
    }
}
