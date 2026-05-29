package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
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

// TODO Replace this list rendering with shared log rendering
// TODO For the compare-with popup, we don't need bookmarks separately
// TODO Although we still need a separate bookmark selector

/**
 * Popup for selecting a bookmark, change, or revision. Used for comparing across revisions or choosing a bookmark.
 * Features:
 * - Shows bookmarks and recent changes (limited to 10 by default)
 * - Real-time search by change ID or description
 * - Search dynamically filters and re-queries to show top 10 matches
 * - Visual icons to distinguish bookmarks from changes
 */
object RevisionSelectorPopup {
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

    /**
     * Show popup to select revision/bookmark for comparison
     * Features search field with dynamic filtering
     * Loads data in background to avoid EDT blocking
     */
    fun show(titleKey: String, repo: JujutsuRepository, filter: Filter, onSelected: (Revision) -> Unit) {
        runLater {
            val panel = createPopupPanel(repo, filter, onSelected)

            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, panel.searchField)
                .setTitle(JujutsuBundle.message(titleKey))
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .createPopup()

            panel.setPopup(popup)
            popup.showCenteredInCurrentWindow(repo.project)
            panel.loadData()
        }
    }

    private fun createPopupPanel(repo: JujutsuRepository, filter: Filter, onSelected: (Revision) -> Unit) =
        PopupPanel(repo, filter, onSelected)

    private class PopupPanel(
        private val repo: JujutsuRepository,
        private var filter: Filter,
        private val onSelected: (Revision) -> Unit
    ) : JPanel(BorderLayout()) {
        val searchField = SearchTextField(false).apply {
            textEditor.emptyText.text = JujutsuBundle.message("dialog.revisionselector.search.emptytext")
        }

        private val listModel = DefaultListModel<RevisionChoice>()
        private val list = object : JBList<RevisionChoice>(listModel) {
            override fun getScrollableTracksViewportWidth() = true

            override fun getToolTipText(event: MouseEvent): String? {
                val index = locationToIndex(event.point)
                if (index < 0) return null

                return when (val item = model.getElementAt(index)) {
                    is RevisionChoice.Change -> htmlString {
                        append(item.entry.id)
                        append(" (")
                        append(item.entry.commitId)
                        append(")\n")
                        item.entry.author?.let {
                            append(it)
                            append("\n")
                        }
                        item.entry.authorTimestamp?.let { timestamp ->
                            append(timestamp)
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
            }
        }.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = RevisionChoiceRenderer()
            visibleRowCount = 15
        }

        private var currentPopup: JBPopup? = null

        init {
            add(searchField, BorderLayout.NORTH)
            val scrollPane = JBScrollPane(list).apply { border = JBUI.Borders.empty() }
            add(scrollPane, BorderLayout.CENTER)
            preferredSize = Dimension(
                JBUI.scale(700),
                scrollPane.preferredSize.height + searchField.preferredSize.height + JBUI.scale(12)
            )

            searchField.addDocumentListener(
                object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        filter = filter.copy(query = searchField.text.trim())
                        loadData()
                    }
                }
            )

            searchField.textEditor.addKeyListener(
                object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        when (e.keyCode) {
                            KeyEvent.VK_DOWN -> {
                                if (listModel.size() > 0) {
                                    val currentIndex = list.selectedIndex
                                    list.selectedIndex =
                                        if (currentIndex < listModel.size() - 1) currentIndex + 1 else 0
                                    list.ensureIndexIsVisible(list.selectedIndex)
                                    e.consume()
                                }
                            }

                            KeyEvent.VK_UP -> {
                                if (listModel.size() > 0) {
                                    val currentIndex = list.selectedIndex
                                    list.selectedIndex =
                                        if (currentIndex > 0) currentIndex - 1 else listModel.size() - 1
                                    list.ensureIndexIsVisible(list.selectedIndex)
                                    e.consume()
                                }
                            }

                            KeyEvent.VK_ENTER -> {
                                if (list.selectedValue != null) {
                                    selectItem(list.selectedValue)
                                    e.consume()
                                }
                            }
                        }
                    }
                }
            )

            list.addKeyListener(
                object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ENTER && list.selectedValue != null) {
                            selectItem(list.selectedValue)
                        }
                    }
                }
            )

            list.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount == 2 && list.selectedValue != null) {
                            selectItem(list.selectedValue)
                        }
                    }
                }
            )
        }

        fun loadData() {
            runInBackground {
                val items = buildItemList(repo, filter)
                runLater {
                    listModel.clear()
                    items.forEach { listModel.addElement(it) }
                    if (listModel.size() > 0) list.selectedIndex = 0
                }
            }
        }

        private fun selectItem(item: RevisionChoice) {
            onSelected(item.revision)
            currentPopup?.cancel()
        }

        fun setPopup(popup: JBPopup) {
            currentPopup = popup
        }
    }

    private class RevisionChoiceRenderer : TextListCellRenderer<RevisionChoice>() {
        override fun render(canvas: TextCanvas, value: RevisionChoice) = canvas.append(value)
    }

    /**
     * Build list of items to show in popup.
     * Should be called from a background thread.
     */
    internal fun buildItemList(repo: JujutsuRepository, filter: Filter): List<RevisionChoice> {
        val items = mutableListOf<RevisionChoice>()

        val bookmarkResult = repo.logService.getBookmarks()
        if (bookmarkResult.isSuccess) {
            val filteredBookmarks = bookmarkResult.getOrNull().orEmpty().filter(filter::matches)
            items.addAll(filteredBookmarks.map(RevisionChoice::Bookmark))
        }

        if (filter.includeLogEntries) {
            val filteredEntries = repo.cachedEntries().filter(filter::matches).take(DEFAULT_LIMIT)
            items.addAll(filteredEntries.map(RevisionChoice::Change))
        }

        return items
    }
}
