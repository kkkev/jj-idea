package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.CommonBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.components.RevisionChoice
import `in`.kkkev.jjidea.ui.components.RevisionSelectorPopup
import `in`.kkkev.jjidea.ui.components.TextCanvas
import `in`.kkkev.jjidea.ui.components.TextListCellRenderer
import `in`.kkkev.jjidea.ui.components.append
import `in`.kkkev.jjidea.ui.components.icon
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

object JujutsuWorkingCopySwitcher {
    internal val defaultFilter = RevisionSelectorPopup.Filter(includeRemote = false, includeLogEntries = true)

    fun createPopup(repo: JujutsuRepository): JBPopup {
        preload(repo)
        val panel = SwitcherPanel(repo, cachedItems[repo.directory.path] ?: emptyList())
        return JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel.searchField)
            .setTitle(JujutsuBundle.message("statusbar.switcher.title"))
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .also { panel.popup = it }
    }

    fun preload(repo: JujutsuRepository) {
        val key = repo.directory.path
        if (!refreshInFlight.add(key)) return
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                cachedItems[key] = loadItems(repo)
            } finally {
                refreshInFlight.remove(key)
            }
        }
    }

    private fun loadItems(repo: JujutsuRepository): List<RevisionChoice> =
        RevisionSelectorPopup.buildItemList(repo, defaultFilter)
            .filter { it !is RevisionChoice.Change || !it.entry.isWorkingCopy }

    /** The revision to pass to jj for this item: unambiguous commit id for changes, bookmark name for bookmarks. */
    internal fun actionRevision(item: RevisionChoice): Revision = when (item) {
        is RevisionChoice.Change -> item.entry.commitId
        is RevisionChoice.Bookmark -> item.item.bookmark
    }

    /** Advisory render hint: true when the item's change id is in the known-immutable set. */
    internal fun immutableHint(item: RevisionChoice, immutableChangeIds: Set<String>): Boolean = when (item) {
        is RevisionChoice.Change -> item.entry.id.full in immutableChangeIds
        is RevisionChoice.Bookmark -> item.item.id?.full in immutableChangeIds
    }

    private fun computeImmutableChangeIds(repo: JujutsuRepository, items: List<RevisionChoice>): Set<String> {
        if (items.isEmpty()) return emptySet()
        val revs = items.map {
            when (it) {
                is RevisionChoice.Change -> it.entry.commitId.full
                is RevisionChoice.Bookmark -> it.item.bookmark.name
            }
        }
        val expr = Expression("(${revs.joinToString("|")}) & immutable()")
        return repo.logService.getLogBasic(revset = expr).getOrNull()?.map { it.id.full }?.toSet() ?: emptySet()
    }

    private enum class SwitchMode { EDIT, NEW, CANCEL }

    private fun chooseSwitchMode(project: Project, entry: LogEntry): SwitchMode {
        val title = JujutsuBundle.message("statusbar.switch.confirm.title")
        val cancel = CommonBundle.getCancelButtonText()
        val newBtn = JujutsuBundle.message("statusbar.switch.confirm.new")
        val icon = Messages.getQuestionIcon()
        return if (entry.immutable) {
            val msg = JujutsuBundle.message(
                "statusbar.switch.confirm.immutable.message",
                entry.id.short,
                entry.description.summary
            )
            if (Messages.showDialog(project, msg, title, arrayOf(newBtn, cancel), 0, icon) == 0) {
                SwitchMode.NEW
            } else {
                SwitchMode.CANCEL
            }
        } else {
            val msg = JujutsuBundle.message(
                "statusbar.switch.confirm.message",
                entry.id.short,
                entry.description.summary
            )
            val edit = JujutsuBundle.message("statusbar.switch.confirm.edit")
            when (Messages.showDialog(project, msg, title, arrayOf(edit, newBtn, cancel), 0, icon)) {
                0 -> SwitchMode.EDIT
                1 -> SwitchMode.NEW
                else -> SwitchMode.CANCEL
            }
        }
    }

    private class SwitcherPanel(
        private val repo: JujutsuRepository,
        initialItems: List<RevisionChoice>
    ) : JPanel(BorderLayout()) {
        val searchField = SearchTextField(false).apply {
            textEditor.emptyText.text = JujutsuBundle.message("dialog.revisionselector.search.emptytext")
        }
        private val listModel = DefaultListModel<RevisionChoice>()
        private val list = object : JBList<RevisionChoice>(listModel) {
            override fun getScrollableTracksViewportWidth() = true
        }.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = ImmutableAwareRenderer()
            visibleRowCount = 15
        }
        var popup: JBPopup? = null
        private var allItems: List<RevisionChoice> = initialItems
        private var immutableChangeIds: Set<String> = emptySet()

        private inner class ImmutableAwareRenderer : TextListCellRenderer<RevisionChoice>() {
            override fun render(canvas: TextCanvas, value: RevisionChoice) {
                if (immutableHint(value, immutableChangeIds)) {
                    canvas.grey {
                        append(value)
                        append(" ")
                        append(icon(JujutsuIcons::Immutable))
                    }
                } else {
                    canvas.append(value)
                }
            }
        }

        init {
            add(searchField, BorderLayout.NORTH)
            val scrollPane = JBScrollPane(list).apply { border = JBUI.Borders.empty() }
            add(scrollPane, BorderLayout.CENTER)
            preferredSize = Dimension(
                JBUI.scale(700),
                scrollPane.preferredSize.height + searchField.preferredSize.height + JBUI.scale(12)
            )
            applyFilter("")
            searchField.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = applyFilter(searchField.text.trim())
            })
            searchField.textEditor.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_DOWN -> navigateList(+1, e)
                        KeyEvent.VK_UP -> navigateList(-1, e)
                        KeyEvent.VK_ENTER -> list.selectedValue?.let {
                            select(it)
                            e.consume()
                        }
                    }
                }
            })
            list.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER) list.selectedValue?.let(::select)
                }
            })
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) list.selectedValue?.let(::select)
                }
            })
            runInBackground {
                val fresh = loadItems(repo)
                val immutable = computeImmutableChangeIds(repo, fresh)
                cachedItems[repo.directory.path] = fresh
                runLater { updateItems(fresh, immutable) }
            }
        }

        private fun navigateList(delta: Int, e: KeyEvent) {
            if (listModel.size() == 0) return
            val idx = list.selectedIndex
            val maxIdx = listModel.size() - 1
            list.selectedIndex = when {
                delta > 0 && idx < maxIdx -> idx + 1
                delta > 0 -> 0
                idx > 0 -> idx - 1
                else -> maxIdx
            }
            list.ensureIndexIsVisible(list.selectedIndex)
            e.consume()
        }

        private fun applyFilter(query: String) {
            val filter = if (query.isEmpty()) null else defaultFilter.copy(query = query)
            val filtered = when (filter) {
                null -> allItems
                else -> allItems.filter {
                    when (it) {
                        is RevisionChoice.Bookmark -> filter.matches(it.item)
                        is RevisionChoice.Change -> filter.matches(it.entry)
                    }
                }
            }
            listModel.clear()
            filtered.forEach { listModel.addElement(it) }
            if (listModel.size() > 0) list.selectedIndex = 0
        }

        private fun updateItems(items: List<RevisionChoice>, immutable: Set<String>) {
            allItems = items
            immutableChangeIds = immutable
            applyFilter(searchField.text.trim())
        }

        private fun select(item: RevisionChoice) {
            popup?.cancel()
            val project = repo.project
            runInBackground {
                val resolved = repo.logService.getLogBasic(revset = actionRevision(item))
                    .getOrNull()?.firstOrNull()
                runLater {
                    if (resolved == null) {
                        Messages.showErrorDialog(
                            project,
                            JujutsuBundle.message("statusbar.switch.resolve.error.message"),
                            JujutsuBundle.message("statusbar.switch.resolve.error.title")
                        )
                        return@runLater
                    }
                    when (chooseSwitchMode(project, resolved)) {
                        SwitchMode.EDIT -> {
                            repo.commandExecutor
                                .createCommand { edit(resolved.commitId) }
                                .onSuccess { repo.invalidate(select = resolved.commitId, vfsChanged = true) }
                                .onFailure { tellUser(project, "statusbar.switch.edit.error") }
                                .executeAsync()
                        }
                        SwitchMode.NEW -> {
                            repo.commandExecutor
                                .createCommand { new(Description.EMPTY, listOf(resolved.commitId)) }
                                .onSuccess { repo.invalidate(select = WorkingCopy, vfsChanged = true) }
                                .onFailure { tellUser(project, "statusbar.switch.new.error") }
                                .executeAsync()
                        }
                        SwitchMode.CANCEL -> {}
                    }
                }
            }
        }
    }

    private val cachedItems = ConcurrentHashMap<String, List<RevisionChoice>>()
    private val refreshInFlight = ConcurrentHashMap.newKeySet<String>()
}
