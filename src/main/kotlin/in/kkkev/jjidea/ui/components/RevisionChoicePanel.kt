package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.cachedEntries
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

private const val DEFAULT_LIMIT = 10

data class Filter(val includeRemote: Boolean, val includeLogEntries: Boolean, val query: String = "") {
    fun matches(bookmark: BookmarkItem) = !bookmark.bookmark.deleted &&
        (!bookmark.bookmark.isRemote || includeRemote) &&
        (
            query.isEmpty() ||
                bookmark.bookmark.name.contains(query, ignoreCase = true) ||
                bookmark.id?.full?.contains(query, ignoreCase = true) == true ||
                bookmark.id?.short?.contains(query, ignoreCase = true) == true
        )

    fun matches(entry: LogEntry) = includeLogEntries &&
        (
            query.isEmpty() ||
                entry.id.short.contains(query, ignoreCase = true) ||
                entry.id.full.contains(query, ignoreCase = true) ||
                entry.commitId.short.contains(query, ignoreCase = true) ||
                entry.commitId.full.contains(query, ignoreCase = true) ||
                entry.description.display.contains(query, ignoreCase = true)
        )
}

internal fun buildRevisionChoices(repo: JujutsuRepository, filter: Filter): List<RevisionChoice> {
    val items = mutableListOf<RevisionChoice>()
    val bookmarkResult = repo.logService.getBookmarks()
    if (bookmarkResult.isSuccess) {
        bookmarkResult.getOrNull().orEmpty()
            .filter(filter::matches)
            .mapTo(items, RevisionChoice::Bookmark)
    }
    if (filter.includeLogEntries) {
        repo.cachedEntries().filter(filter::matches).take(DEFAULT_LIMIT)
            .mapTo(items, RevisionChoice::Change)
    }
    return items
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

abstract class RevisionChoicePanel(
    protected val repo: JujutsuRepository,
    initialFilter: Filter
) : JPanel(BorderLayout()) {
    val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = JujutsuBundle.message("dialog.revisionselector.search.emptytext")
    }

    private val listModel = DefaultListModel<RevisionChoice>()
    private val list = object : JBList<RevisionChoice>(listModel) {
        override fun getScrollableTracksViewportWidth() = true
        override fun getToolTipText(event: MouseEvent): String? {
            val index = locationToIndex(event.point)
            return if (index < 0) null else tooltipFor(model.getElementAt(index))
        }
    }.apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = MutabilityAwareRenderer()
        visibleRowCount = 15
    }

    private var filter: Filter = initialFilter
    private var popup: JBPopup? = null
    private var immutableChangeIds: Set<String> = emptySet()
    private var allEntries: List<LogEntry> = emptyList()

    init {
        add(searchField, BorderLayout.NORTH)
        val scrollPane = JBScrollPane(list).apply { border = JBUI.Borders.empty() }
        add(scrollPane, BorderLayout.CENTER)
        preferredSize = Dimension(
            JBUI.scale(700),
            scrollPane.preferredSize.height + searchField.preferredSize.height + JBUI.scale(12)
        )

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filter = filter.copy(query = searchField.text.trim())
                loadData()
            }
        })

        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> navigate(+1, e)
                    KeyEvent.VK_UP -> navigate(-1, e)
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
    }

    abstract fun onSelect(item: RevisionChoice)

    open fun buildItems(filter: Filter): List<RevisionChoice> = buildRevisionChoices(repo, filter)

    open fun tooltipFor(item: RevisionChoice): String? = when (item) {
        is RevisionChoice.Change -> htmlString {
            append(item.entry.id)
            append(" (")
            append(item.entry.commitId)
            append(")\n")
            item.entry.author?.let {
                append(it)
                append("\n")
            }
            item.entry.authorTimestamp?.let {
                append(it)
                append("\n")
            }
            append("\n")
            appendSummary(item.entry.description)
        }
        is RevisionChoice.Bookmark -> htmlString {
            append(item.item.bookmark)
            item.item.id?.let { id ->
                append(" (")
                append(id)
                append(")")
            }
        }
    }

    fun setPopup(popup: JBPopup) {
        this.popup = popup
    }

    fun loadData() {
        runInBackground {
            val entries = repo.cachedEntries()
            val items = buildItems(filter)
            val immutable = computeImmutableChangeIds(repo, items)
            runLater {
                allEntries = entries
                immutableChangeIds = immutable
                listModel.clear()
                items.forEach { listModel.addElement(it) }
                if (listModel.size() > 0) list.selectedIndex = 0
            }
        }
    }

    private fun select(item: RevisionChoice) {
        popup?.cancel()
        onSelect(item)
    }

    private fun navigate(delta: Int, e: KeyEvent) {
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

    private inner class MutabilityAwareRenderer : TextListCellRenderer<RevisionChoice>() {
        override fun render(canvas: TextCanvas, value: RevisionChoice) {
            val isImmutable = when (value) {
                is RevisionChoice.Change -> value.entry.id.full in immutableChangeIds
                is RevisionChoice.Bookmark -> value.item.id?.full in immutableChangeIds
            }
            val isWorkingCopy = value is RevisionChoice.Change && value.entry.isWorkingCopy
            fun doRender() {
                canvas.append(icon(if (isImmutable) JujutsuIcons::Immutable else JujutsuIcons::Mutable))
                canvas.append(" ")
                canvas.append(value, allEntries)
            }
            if (isWorkingCopy) canvas.bold { doRender() } else doRender()
        }
    }
}
