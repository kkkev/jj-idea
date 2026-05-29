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
        // Create UI on EDT
        runLater {
            val panel = createPopupPanel(repo, filter, onSelected)

            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, panel.searchField)
                .setTitle(JujutsuBundle.message(titleKey))
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .createPopup()

            // Set popup reference so panel can close it
            panel.setPopup(popup)

            popup.showCenteredInCurrentWindow(repo.project)

            // Load initial data after popup is shown
            panel.loadData()
        }
    }

    /**
     * Create the popup panel with search field and list
     */
    private fun createPopupPanel(repo: JujutsuRepository, filter: Filter, onSelected: (Revision) -> Unit) =
        PopupPanel(repo, filter, onSelected)

    /**
     * Panel containing search field and results list
     */
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
            // Make list fill viewport width instead of expanding to content width
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
            visibleRowCount = 15 // Show 15 items without scrolling
        }

        private var currentPopup: JBPopup? = null

        init {
            // Search field
            add(searchField, BorderLayout.NORTH)

            // Results list - fills available panel space
            val scrollPane = JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
            }
            add(scrollPane, BorderLayout.CENTER)

            // Set preferred width only - let height be determined by list's visibleRowCount
            // Add small buffer for borders and padding
            preferredSize = Dimension(
                JBUI.scale(700),
                scrollPane.preferredSize.height + searchField.preferredSize.height + JBUI.scale(12)
            )

            // Listen to search field changes
            searchField.addDocumentListener(
                object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        filter = filter.copy(query = searchField.text.trim())
                        loadData()
                    }
                }
            )

            // Handle up/down keys in search field to navigate list
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

            // Handle Enter key to select
            list.addKeyListener(
                object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ENTER && list.selectedValue != null) {
                            selectItem(list.selectedValue)
                        }
                    }
                }
            )

            // Handle double-click to select
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

        /**
         * Load and filter data based on search query
         */
        fun loadData() {
            runInBackground {
                val items = buildItemList(repo, filter)

                runLater {
                    listModel.clear()
                    items.forEach { listModel.addElement(it) }

                    // Select first item by default
                    if (listModel.size() > 0) {
                        list.selectedIndex = 0
                    }
                }
            }
        }

        /**
         * Select an item and close popup
         */
        private fun selectItem(item: RevisionChoice) {
            onSelected(item.revision)
            // Close the popup
            currentPopup?.cancel()
        }

        /**
         * Set the popup reference so we can close it
         */
        fun setPopup(popup: JBPopup) {
            currentPopup = popup
        }
    }

    /**
     * Custom renderer for revision choices with icons
     */
    private class RevisionChoiceRenderer : TextListCellRenderer<RevisionChoice>() {
        override fun render(canvas: TextCanvas, value: RevisionChoice) = canvas.append(value)
    }

    /**
     * Build list of items to show in popup
     * Should be called from background thread
     * Filters based on query and limits results
     */
    internal fun buildItemList(repo: JujutsuRepository, filter: Filter): List<RevisionChoice> {
        val items = mutableListOf<RevisionChoice>()

        // Add bookmarks - always show all bookmarks filtered by query
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
