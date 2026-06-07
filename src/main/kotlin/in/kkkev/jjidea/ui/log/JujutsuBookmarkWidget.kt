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

class JujutsuBookmarkWidget(project: Project) : JujutsuFilterComponent("Bookmark"), Disposable {
    private var wcEntries: List<LogEntry> = emptyList()

    /**
     * Bookmarks per repository — sourced from [in.kkkev.jjidea.jj.JujutsuStateModel.references] and kept current via
     * subscription.
     */
    private var bookmarksByRepo: Map<JujutsuRepository, List<BookmarkItem>> = emptyMap()

    init {
        initUi()
        wcEntries = project.stateModel.workingCopies.value.values.toList()
        project.stateModel.workingCopies.connect(this) { copies ->
            wcEntries = copies.values.toList()
            repaint()
        }
        bookmarksByRepo = project.stateModel.references.value.mapValues { it.value.bookmarks }
        project.stateModel.references.connect(this) { references ->
            bookmarksByRepo = references.mapValues { it.value.bookmarks }
            repaint()
        }
    }

    override fun getCurrentText(): String {
        if (wcEntries.size != 1) return ""
        val bookmarks = wcEntries.first().bookmarks.filter { !it.isRemote }
        val text = bookmarks.joinToString(", ") { it.name.name }
        return if (text.length > 30) text.take(29) + "…" else text
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
            BackgroundActionGroup(
                *repoActionGroup(
                    repos.firstOrNull(),
                    wcEntries.firstOrNull(),
                    allGroups,
                    wcBookmarkNames,
                    repoByBookmark
                ).toTypedArray()
            )
        } else {
            val items: List<AnAction> = repos.map { repo ->
                val repoGroups = bookmarksByRepo[repo].orEmpty().map { it.bookmark }.grouped()
                val wcEntry = wcEntries.firstOrNull { it.repo == repo }
                DefaultActionGroup(repo.displayName, true).apply {
                    repoActionGroup(repo, wcEntry, repoGroups, wcBookmarkNames, repoByBookmark).forEach(::add)
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
        repoByBookmark: Map<String, JujutsuRepository>
    ): List<AnAction> = buildList {
        add(createBookmarkAction(wcEntry))
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
                if (!onWc) add(moveBookmarkToChangeAction(repo, local))
                add(renameBookmarkAction(repo, local))
                add(deleteBookmarkAction(repo, local))
                add(forgetBookmarkAction(repo, local))
            }
            group.remotes.forEach { remote ->
                add(toggleTrackBookmarkAction(repo, remote))
            }
        }

    override fun dispose() = Unit
}
