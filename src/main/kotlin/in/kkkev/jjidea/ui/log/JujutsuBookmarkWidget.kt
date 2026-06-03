package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.actions.bookmark.createBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.deleteBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.forgetBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.moveBookmarkToChangeAction
import `in`.kkkev.jjidea.actions.bookmark.renameBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.toggleTrackBookmarkAction
import `in`.kkkev.jjidea.jj.BookmarkGroup
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.grouped
import `in`.kkkev.jjidea.jj.stateModel

class JujutsuBookmarkWidget(
    project: Project,
    private val logTable: JujutsuLogTable
) : JujutsuFilterComponent("Bookmark"), Disposable {
    private var wcEntries: List<LogEntry> = emptyList()

    init {
        initUi()
        wcEntries = project.stateModel.workingCopies.value.values.toList()
        project.stateModel.workingCopies.connect(this) { copies ->
            wcEntries = copies.values.toList()
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

        val repoByBookmark: Map<String, JujutsuRepository> = logTable.logModel.getAllEntries()
            .flatMap { entry -> entry.bookmarks.map { it.name.name to entry.repo } }
            .toMap()

        val allGroups = logTable.logModel.getAllEntries()
            .flatMap { it.bookmarks }
            .distinctBy { it.name }
            .grouped()

        val repos = logTable.logModel.getAllEntries().map { it.repo }.distinct()

        return if (repos.size <= 1) {
            val wcEntry = wcEntries.firstOrNull()
            BackgroundActionGroup(
                *repoActionGroup(repos.firstOrNull(), wcEntry, allGroups, wcBookmarkNames, repoByBookmark)
                    .toTypedArray()
            )
        } else {
            val items: List<AnAction> = repos.map { repo ->
                val repoGroups = allGroups.filter {
                    repoByBookmark[it.localName] == repo || repoByBookmark[it.remotes.firstOrNull()?.name?.name] == repo
                }
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
