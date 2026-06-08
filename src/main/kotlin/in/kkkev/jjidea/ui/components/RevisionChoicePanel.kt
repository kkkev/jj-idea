package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RepositoryReferences
import `in`.kkkev.jjidea.jj.TagItem
import `in`.kkkev.jjidea.jj.stateModel
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

// Hoisted to avoid per-render reflection (IconSpec.name eagerly calls icon.javaField).
private val ICON_MUTABLE = icon(JujutsuIcons::Mutable)
private val ICON_IMMUTABLE = icon(JujutsuIcons::Immutable)

data class Filter(val includeRemote: Boolean, val includeLogEntries: Boolean, val query: String = "") {
    private fun matchesQuery(name: String, id: ChangeId?) =
        query.isEmpty() ||
            name.contains(query, ignoreCase = true) ||
            id?.full?.contains(query, ignoreCase = true) == true ||
            id?.short?.contains(query, ignoreCase = true) == true

    fun matches(bookmark: BookmarkItem) = !bookmark.bookmark.deleted &&
        (!bookmark.bookmark.isRemote || includeRemote) &&
        matchesQuery(bookmark.bookmark.name.name, bookmark.id)

    fun matches(tag: TagItem) = matchesQuery(tag.tag.name, tag.id)

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
    val references = repo.project.stateModel.references.value[repo] ?: RepositoryReferences()
    references.bookmarks.filter(filter::matches).mapTo(items, RevisionChoice::Ref)
    references.tags.filter(filter::matches).mapTo(items, RevisionChoice::Ref)
    if (filter.includeLogEntries) {
        repo.logCache.all.filter(filter::matches).take(DEFAULT_LIMIT)
            .mapTo(items, RevisionChoice::Change)
    }
    return items
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
    private var immutableTagNames: Set<String> = emptySet()
    private var allEntries: List<LogEntry> = emptyList()

    // Incremented on every loadData() call; EDT updates that arrive after a newer call was
    // dispatched are dropped, preventing stale results from overwriting a more recent load.
    @Volatile private var loadVersion = 0

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
        is RevisionChoice.Ref -> htmlString {
            append(item.item)
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
        val version = ++loadVersion
        runInBackground {
            val entries = repo.logCache.all
            val items = buildItems(filter)
            val immutableIds = entries.filter { it.immutable }.map { it.id.full }.toSet()
            val immutableTags = entries
                .filter { it.immutable }
                .flatMap { e -> e.tags.map { it.name } }
                .toSet()
            runLater {
                if (version != loadVersion) return@runLater
                allEntries = entries
                immutableChangeIds = immutableIds
                immutableTagNames = immutableTags
                listModel.clear()
                listModel.addAll(items)
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
                is RevisionChoice.Ref -> when (val item = value.item) {
                    is TagItem -> item.tag.name in immutableTagNames
                    else -> item.id?.full in immutableChangeIds
                }
            }
            val isWorkingCopy = value is RevisionChoice.Change && value.entry.isWorkingCopy
            fun doRender() {
                canvas.append(if (isImmutable) ICON_IMMUTABLE else ICON_MUTABLE)
                canvas.append(" ")
                canvas.append(value, allEntries)
            }
            if (isWorkingCopy) canvas.bold { doRender() } else doRender()
        }
    }
}
