package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.jj.BookmarkGroup
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ClosestBookmarks
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.grouped
import `in`.kkkev.jjidea.ui.common.RepositoryIcons

/**
 * Builds the bookmark management popup shared by every surface that shows a bookmark
 * dropdown: the main-toolbar widget ([in.kkkev.jjidea.ui.toolbar.JujutsuBookmarkToolbarWidget])
 * and its status-bar fallback ([in.kkkev.jjidea.ui.statusbar.JujutsuBookmarkStatusBarWidget]).
 * Extracted from the original log-toolbar `JujutsuBookmarkWidget` (jj-idea-cpno / jj-idea-kd44)
 * so both surfaces share one implementation rather than diverging.
 *
 * Single-repo projects get a flat menu; multi-repo projects get one submenu per repo, titled
 * with the repo's display name and icon.
 */
fun bookmarkActionGroup(
    wcEntries: List<LogEntry>,
    bookmarksByRepo: Map<JujutsuRepository, List<BookmarkItem>>,
    closestByRepo: Map<JujutsuRepository, ClosestBookmarks?>
): ActionGroup {
    val wcBookmarkNames = wcEntries.flatMap { it.bookmarks.map { b -> b.name.name } }.toSet()
    val repos = bookmarksByRepo.keys.toList()
    val repoByBookmark: Map<String, JujutsuRepository> = bookmarksByRepo.entries
        .flatMap { (repo, items) -> items.map { it.bookmark.name.name to repo } }
        .toMap()

    return if (repos.size <= 1) {
        val repo = repos.firstOrNull()
        val allGroups = bookmarksByRepo.values.flatten().map { it.bookmark }.distinctBy { it.name }.grouped()
        BackgroundActionGroup(
            *repoActionGroup(
                repo,
                wcEntries.firstOrNull(),
                allGroups,
                wcBookmarkNames,
                repoByBookmark,
                closestByRepo[repo]
            ).toTypedArray()
        )
    } else {
        val items: List<AnAction> = repos.map { repo ->
            val repoGroups = bookmarksByRepo[repo].orEmpty().map { it.bookmark }.grouped()
            val wcEntry = wcEntries.firstOrNull { it.repo == repo }
            DefaultActionGroup(repo.displayName, true).apply {
                templatePresentation.icon = RepositoryIcons[repo]
                repoActionGroup(repo, wcEntry, repoGroups, wcBookmarkNames, repoByBookmark, closestByRepo[repo])
                    .forEach(::add)
            }
        }
        BackgroundActionGroup(*items.toTypedArray())
    }
}

private fun repoActionGroup(
    repo: JujutsuRepository?,
    wcEntry: LogEntry?,
    groups: List<BookmarkGroup>,
    wcBookmarkNames: Set<String>,
    repoByBookmark: Map<String, JujutsuRepository>,
    closest: ClosestBookmarks?
): List<AnAction> = buildList {
    add(createBookmarkAction(wcEntry))
    add(advanceClosestBookmarkAction(repo, closest))
    if (groups.isNotEmpty()) {
        add(Separator.create())
        groups.forEach { group ->
            val groupRepo = repo
                ?: repoByBookmark[group.localName]
                ?: repoByBookmark[group.remotes.firstOrNull()?.name?.name]
                ?: return@forEach
            add(bookmarkSubGroup(group, groupRepo, onWc = group.localName in wcBookmarkNames))
        }
    }
}

private fun bookmarkSubGroup(group: BookmarkGroup, repo: JujutsuRepository, onWc: Boolean) =
    DefaultActionGroup(group.localName, true).apply {
        group.local?.let { local ->
            localBookmarkActions(
                repo,
                local,
                includeMoveToChange = !onWc,
                remoteBookmarks = group.remotes
            ).forEach(::add)
        }
        group.remotes.forEach { remote -> remoteBookmarkActions(repo, remote).forEach(::add) }
    }

private const val MAX_WIDGET_TEXT_LENGTH = 30

/**
 * The bookmark widget's toolbar label: bookmark(s) sitting exactly on the working copy when there
 * are any, otherwise the nearest ancestor bookmark(s) and how far behind `@` they are (e.g.
 * `"main +3"`), so the widget never just goes blank once `@` moves past every bookmark (jj-idea-l7wd
 * / GitHub #62). Empty when there's no working copy or no bookmark anywhere in its ancestry.
 *
 * A pure function so it's testable without a platform test.
 */
fun bookmarkWidgetText(bookmarksOnWorkingCopy: List<String>, closest: ClosestBookmarks?): String {
    val text = if (bookmarksOnWorkingCopy.isNotEmpty()) {
        joinTruncated(bookmarksOnWorkingCopy)
    } else if (closest != null) {
        val namesText = joinTruncated(closest.names.map { it.name })
        val cappedMarker = if (closest.distanceCapped) "+" else ""
        "$namesText +${closest.distance}$cappedMarker"
    } else {
        return ""
    }
    return if (text.length > MAX_WIDGET_TEXT_LENGTH) {
        text.take(MAX_WIDGET_TEXT_LENGTH - 1) + "…"
    } else {
        text
    }
}

private fun joinTruncated(names: List<String>) = buildString {
    for (name in names) {
        if (length >= MAX_WIDGET_TEXT_LENGTH) break
        if (isNotEmpty()) append(", ")
        append(name)
    }
}
