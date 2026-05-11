package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.concurrency.AppExecutorUtil
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogCache
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate
import java.util.concurrent.ConcurrentHashMap

object JujutsuWorkingCopySwitcher {
    sealed class SwitchItem {
        abstract val displayName: String
        open val selectable: Boolean = true
    }

    sealed class SelectableSwitchItem : SwitchItem() {
        abstract val revision: Revision
        abstract val immutable: Boolean
    }

    data class InfoEntry(
        override val displayName: String
    ) : SwitchItem() {
        override val selectable: Boolean = false
    }

    data class BookmarkEntry(
        val item: BookmarkItem,
        override val immutable: Boolean
    ) : SelectableSwitchItem() {
        override val revision: Revision = item.bookmark
        override val displayName: String = item.bookmark.name
    }

    data class ChangeEntry(val entry: LogEntry) : SelectableSwitchItem() {
        override val revision: Revision = entry.id
        override val immutable: Boolean = entry.immutable
        override val displayName: String =
            if (!entry.description.empty) {
                "${entry.id.short} ${entry.description.summary}"
            } else {
                "(${entry.id.short})"
            }
        }

    fun createPopup(repo: JujutsuRepository): ListPopup {
        preload(repo)
        val items = cachedItems[repo.directory.path] ?: listOf(InfoEntry("Loading recent changes..."))
        val step = SwitchPopupStep(items, repo)
        return JBPopupFactory.getInstance().createListPopup(step)
    }

    fun preload(repo: JujutsuRepository) {
        val key = repo.directory.path
        if (!refreshInFlight.add(key)) return
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                cachedItems[key] = loadItemList(repo)
            } finally {
                refreshInFlight.remove(key)
            }
        }
    }

    internal fun loadItemList(repo: JujutsuRepository): List<SwitchItem> {
        val cache = LogCache.getInstance(repo.project)
        val logEntries = cache.get(Expression.ALL)
            ?: repo.logService.getLogBasic(revset = Expression.ALL)
                .getOrNull()
                ?.also { cache.put(Expression.ALL, emptyList(), it) }
            ?: emptyList()
        val bookmarks = repo.logService.getBookmarks().getOrNull() ?: emptyList()
        return buildItemList(logEntries, bookmarks)
    }

    internal fun buildItemList(logEntries: List<LogEntry>, bookmarks: List<BookmarkItem>): List<SwitchItem> {
        val immutabilityById = logEntries.associate { it.id to it.immutable }
        val bookmarkItems = bookmarks
            .filter { !it.bookmark.isRemote }
            .map { BookmarkEntry(it, immutabilityById[it.id] ?: false) }
        val changeItems = logEntries
            .filter { !it.isWorkingCopy }
            .take(10)
            .map { ChangeEntry(it) }
        return bookmarkItems + changeItems
    }

    private class SwitchPopupStep(
        private val items: List<SwitchItem>,
        private val repo: JujutsuRepository
    ) : BaseListPopupStep<SwitchItem>("Switch Working Copy", items) {
        override fun getTextFor(value: SwitchItem) = value.displayName

        override fun isSelectable(value: SwitchItem): Boolean = value.selectable

        override fun isSpeedSearchEnabled() = true

        override fun onChosen(selectedValue: SwitchItem, finalChoice: Boolean): PopupStep<*>? {
            if (!finalChoice) return null
            val actionable = selectedValue as? SelectableSwitchItem ?: return FINAL_CHOICE
            doFinalStep { switchTo(actionable) }
            return FINAL_CHOICE
        }

        private fun switchTo(item: SelectableSwitchItem) {
            val project = repo.project
            if (item.immutable) {
                repo.commandExecutor
                    .createCommand { new(Description.EMPTY, listOf(item.revision)) }
                    .onSuccess { repo.invalidate(select = WorkingCopy, vfsChanged = true) }
                    .onFailure { tellUser(project, "statusbar.switch.new.error") }
                    .executeAsync()
            } else {
                repo.commandExecutor
                    .createCommand { edit(item.revision) }
                    .onSuccess { repo.invalidate(select = item.revision, vfsChanged = true) }
                    .onFailure { tellUser(project, "statusbar.switch.edit.error") }
                    .executeAsync()
            }
        }
    }

    private val cachedItems = ConcurrentHashMap<String, List<SwitchItem>>()
    private val refreshInFlight = ConcurrentHashMap.newKeySet<String>()
}
