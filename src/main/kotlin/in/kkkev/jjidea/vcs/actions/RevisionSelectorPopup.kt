package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.components.*
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

    /**
     * Item in the comparison popup
     */
    sealed class CompareItem(open val displayName: String, open val revision: Revision) {
        /** Recent change with description */
        data class Change(
            val entry: LogEntry,
            override val displayName: String = "${entry.id.short} ${entry.description.summary}",
            override val revision: Revision = entry.id
        ) : CompareItem(displayName, revision) {
            val id: ChangeId get() = entry.id
            val description: Description get() = entry.description
        }

        /** Named bookmark with change ID */
        data class Bookmark(
            val item: BookmarkItem,
            override val displayName: String = "${item.bookmark.name} (${item.id.short})",
            override val revision: Revision = item.bookmark
        ) : CompareItem(displayName, revision)
    }

    /**
     * Show popup to select revision/bookmark for comparison
     * Features search field with dynamic filtering
     * Loads data in background to avoid EDT blocking
     */
    fun show(repo: JujutsuRepository, includeLogEntries: Boolean, onSelected: (Revision) -> Unit) {
        // Create UI on EDT
        ApplicationManager.getApplication().invokeLater {
            val panel = createPopupPanel(repo, includeLogEntries, onSelected)

            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, panel.searchField)
                .setTitle("Select Branch or Change to Compare")
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .createPopup()

            // Set popup reference so panel can close it
            panel.setPopup(popup)

            popup.showCenteredInCurrentWindow(repo.project)

            // Load initial data after popup is shown
            panel.loadData("")
        }
    }

    /**
     * Create the popup panel with search field and list
     */
    private fun createPopupPanel(repo: JujutsuRepository, includeLogEntries: Boolean, onSelected: (Revision) -> Unit) =
        PopupPanel(repo, includeLogEntries, onSelected)

    /**
     * Panel containing search field and results list
     */
    private class PopupPanel(
        private val repo: JujutsuRepository,
        private val includeLogEntries: Boolean,
        private val onSelected: (Revision) -> Unit
    ) : JPanel(BorderLayout()) {
        val searchField = SearchTextField(false).apply {
            textEditor.emptyText.text = "Search by change ID or description..."
        }

        private val listModel = DefaultListModel<CompareItem>()
        private val list = object : JBList<CompareItem>(listModel) {
            // Make list fill viewport width instead of expanding to content width
            override fun getScrollableTracksViewportWidth() = true

            override fun getToolTipText(event: MouseEvent): String? {
                val index = locationToIndex(event.point)
                if (index < 0) return null

                return when (val item = model.getElementAt(index)) {
                    is CompareItem.Change -> htmlString {
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

                    is CompareItem.Bookmark -> htmlString {
                        append(item.item.bookmark)
                        append(" (")
                        append(item.item.id)
                        append(")")
                    }

                    else -> null
                }
            }
        }.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = CompareItemRenderer()
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
                        loadData(searchField.text)
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
        fun loadData(query: String) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val items = buildItemList(repo, includeLogEntries, query.trim())

                ApplicationManager.getApplication().invokeLater {
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
        private fun selectItem(item: CompareItem) {
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
     * Custom renderer for compare items with icons
     */
    private class CompareItemRenderer : TextListCellRenderer<CompareItem>() {
        override fun render(canvas: TextCanvas, value: CompareItem) = with(canvas) {
            when (value) {
                is CompareItem.Bookmark -> {
                    append(value.item.bookmark)
                    append(" (")
                    append(value.item.id)
                    append(")")
                }

                is CompareItem.Change -> {
                    append(icon(AllIcons.Vcs::CommitNode))
                    append(value.id)
                    append(" ")
                    append(value.description)
                }
            }
        }
    }

    /**
     * Build list of items to show in popup
     * Should be called from background thread
     * Filters based on query and limits results
     *
     * @param query Search query to filter changes by change ID or description
     */
    // TODO Passing includeLogEntries around a lot
    internal fun buildItemList(repo: JujutsuRepository, includeLogEntries: Boolean, query: String): List<CompareItem> {
        val items = mutableListOf<CompareItem>()
        val cache = LogCache.getInstance(repo.project)

        // Add bookmarks - always show all bookmarks filtered by query
        val logService = repo.logService
        val bookmarkResult = logService.getBookmarks()
        if (bookmarkResult.isSuccess) {
            val bookmarks = bookmarkResult.getOrNull() ?: emptyList()

            // Filter bookmarks by query
            val filteredBookmarks = if (query.isEmpty()) {
                bookmarks
            } else {
                bookmarks.filter { bookmark ->
                    bookmark.bookmark.name.contains(query, ignoreCase = true) ||
                        bookmark.id.full.contains(query, ignoreCase = true) ||
                        bookmark.id.short.contains(query, ignoreCase = true)
                }
            }

            items.addAll(filteredBookmarks.map { CompareItem.Bookmark(it) })
        }

        if (includeLogEntries) {
            // Add recent changes - limit to DEFAULT_LIMIT and filter by query
            // Try to get from cache first
            val entries = cache.get(Expression.ALL) ?: run {
                // Not in cache, fetch from jj and cache it
                val logResult = logService.getLogBasic(revset = Expression.ALL)
                logResult.getOrNull()?.also { fetchedEntries ->
                    cache.put(Expression.ALL, emptyList(), fetchedEntries)
                } ?: emptyList()
            }

            // Filter changes by query
            val filteredEntries = if (query.isEmpty()) {
                entries.take(DEFAULT_LIMIT)
            } else {
                entries
                    .filter { entry ->
                        entry.id.short.contains(query, ignoreCase = true) ||
                            entry.id.full.contains(query, ignoreCase = true) ||
                            entry.description.display.contains(query, ignoreCase = true)
                    }.take(DEFAULT_LIMIT)
            }

            // Convert to CompareItems
            filteredEntries.forEach { entry ->
                items.add(CompareItem.Change(entry))
            }
        }

        return items
    }
}
