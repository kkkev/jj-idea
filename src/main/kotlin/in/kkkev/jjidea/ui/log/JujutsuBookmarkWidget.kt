package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.actions.bookmark.*
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.common.RepositoryIcons

class JujutsuBookmarkWidget(project: Project) : JujutsuFilterComponent("Bookmark"), Disposable {
    private var wcEntries: List<LogEntry> = emptyList()

    /**
     * Bookmarks per repository — sourced from [in.kkkev.jjidea.jj.JujutsuStateModel.references] and kept current via
     * subscription.
     */
    private var bookmarksByRepo: Map<JujutsuRepository, List<BookmarkItem>> = emptyMap()

    /**
     * Bookmark(s) nearest the working copy when none sit exactly on it — sourced from
     * [in.kkkev.jjidea.jj.JujutsuStateModel.closestBookmarks]. Drives both the "name +N" fallback in
     * [getCurrentText] and the Advance action's target, so the widget never just goes blank once `@` moves
     * past every bookmark (jj-idea-l7wd / GitHub #62).
     */
    private var closestByRepo: Map<JujutsuRepository, ClosestBookmarks?> = emptyMap()

    init {
        initUi()
        wcEntries = project.stateModel.workingCopies.value.values.toList()
        project.stateModel.workingCopies.connect(this) { copies ->
            wcEntries = copies.values.toList()
            refreshPresentation()
        }
        bookmarksByRepo = project.stateModel.references.value.mapValues { it.value.bookmarks }
        project.stateModel.references.connect(this) { references ->
            bookmarksByRepo = references.mapValues { it.value.bookmarks }
            refreshPresentation()
        }
        closestByRepo = project.stateModel.closestBookmarks.value
        project.stateModel.closestBookmarks.connect(this) { closest ->
            closestByRepo = closest
            refreshPresentation()
        }
    }

    override fun getCurrentText(): String {
        if (wcEntries.size != 1) return ""
        val wcEntry = wcEntries.first()
        val onWc = wcEntry.bookmarks.filterNot { it.isRemote }.map { it.name.name }
        return bookmarkWidgetText(onWc, closestByRepo[wcEntry.repo])
    }

    override fun isValueSelected() = false

    override fun doResetFilter() = Unit

    override fun createActionGroup(): ActionGroup {
        val wcBookmarkNames = wcEntries.flatMap { it.bookmarks.map { b -> b.name.name } }.toSet()
        val repos = bookmarksByRepo.keys.toList()
        val repoByBookmark: Map<String, JujutsuRepository> = bookmarksByRepo.entries
            .flatMap { (repo, items) -> items.map { it.bookmark.name.name to repo } }
            .toMap()
        val allGroups = bookmarksByRepo.values.flatten().map { it.bookmark }.distinctBy { it.name }.grouped()

        return if (repos.size <= 1) {
            val repo = repos.firstOrNull()
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
            group.local?.let { local -> localBookmarkActions(repo, local, includeMoveToChange = !onWc).forEach(::add) }
            group.remotes.forEach { remote -> remoteBookmarkActions(repo, remote).forEach(::add) }
        }

    override fun dispose() = Unit
}

private const val MAX_WIDGET_TEXT_LENGTH = 30

/**
 * The bookmark widget's toolbar label: bookmark(s) sitting exactly on the working copy when there
 * are any, otherwise the nearest ancestor bookmark(s) and how far behind `@` they are (e.g.
 * `"main +3"`), so the widget never just goes blank once `@` moves past every bookmark (jj-idea-l7wd
 * / GitHub #62). Empty when there's no working copy or no bookmark anywhere in its ancestry.
 *
 * A pure function (rather than inline in [JujutsuBookmarkWidget.getCurrentText]) so it's testable
 * without a platform test.
 */
internal fun bookmarkWidgetText(bookmarksOnWorkingCopy: List<String>, closest: ClosestBookmarks?): String {
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
