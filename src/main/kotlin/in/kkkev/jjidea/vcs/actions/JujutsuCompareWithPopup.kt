package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.DescriptionRenderer
import `in`.kkkev.jjidea.ui.JujutsuCommitFormatter
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * Popup for selecting a bookmark, change, or revision to compare with
 * Features:
 * - Shows bookmarks and recent changes (limited to 10 by default)
 * - Real-time search by change ID or description
 * - Search dynamically filters and re-queries to show top 10 matches
 * - Visual icons to distinguish bookmarks from changes
 */
object JujutsuCompareWithPopup {

    private const val DEFAULT_LIMIT = 10

    /**
     * Item in the comparison popup
     */
    sealed class CompareItem(open val displayName: String, open val revision: String) {
        /** Recent change with description */
        data class Change(
            val entry: LogEntry,
            override val displayName: String = "${entry.changeId.short} ${entry.description.summary}",
            override val revision: String = entry.changeId.toString()
        ) : CompareItem(displayName, revision) {
            val changeId: ChangeId get() = entry.changeId
            val description: Description get() = entry.description
        }

        /** Named bookmark with change ID */
        data class Bookmark(
            val item: BookmarkItem,
            override val displayName: String = "${item.bookmark.name} (${item.changeId.short})",
            override val revision: String = item.bookmark.name
        ) : CompareItem(displayName, revision)
    }

    /**
     * Show popup to select revision/bookmark for comparison
     * Features search field with dynamic filtering
     * Loads data in background to avoid EDT blocking
     */
    fun show(
        project: Project,
        vcs: JujutsuVcs,
        onSelected: (String) -> Unit
    ) {
        // Create UI on EDT
        ApplicationManager.getApplication().invokeLater {
            val panel = createPopupPanel(project, vcs, onSelected)

            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, panel.searchField)
                .setTitle("Select Branch or Change to Compare")
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .createPopup()

            // Set popup reference so panel can close it
            panel.setPopup(popup)

            popup.showCenteredInCurrentWindow(project)

            // Load initial data after popup is shown
            panel.loadData("")
        }
    }

    /**
     * Create the popup panel with search field and list
     */
    private fun createPopupPanel(
        project: Project,
        vcs: JujutsuVcs,
        onSelected: (String) -> Unit
    ): PopupPanel = PopupPanel(project, vcs, onSelected)

    /**
     * Panel containing search field and results list
     */
    private class PopupPanel(
        private val project: Project,
        private val vcs: JujutsuVcs,
        private val onSelected: (String) -> Unit
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

                val item = model.getElementAt(index)
                return when (item) {
                    is CompareItem.Change -> {
                        val formatted = JujutsuCommitFormatter.format(item.entry.changeId)
                        buildString {
                            append("<html>")
                            append("<b>${formatted.shortPart}</b>")
                            if (formatted.restPart.isNotEmpty()) {
                                append("<font color=gray><small>${formatted.restPart}</small></font>")
                            }
                            append("<br>")
                            item.entry.author?.let { author ->
                                append("${author.name} &lt;${author.email}&gt;<br>")
                            }
                            item.entry.authorTimestamp?.let { timestamp ->
                                append("${timestamp}<br>")
                            }
                            append("<br>")
                            append(DescriptionRenderer.toHtml(item.entry.description, multiline = true))
                            append("</html>")
                        }
                    }

                    is CompareItem.Bookmark -> {
                        val formatted = JujutsuCommitFormatter.format(item.item.changeId)
                        buildString {
                            append("<html>")
                            append("<b>${item.item.bookmark.name}</b><br>")
                            append("Change: <b>${formatted.shortPart}</b>")
                            if (formatted.restPart.isNotEmpty()) {
                                append("<font color=gray><small>${formatted.restPart}</small></font>")
                            }
                            append("</html>")
                        }
                    }

                    else -> null
                }
            }
        }.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = CompareItemRenderer()
            visibleRowCount = 15  // Show 15 items without scrolling
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
            preferredSize =
                Dimension(JBUI.scale(700), scrollPane.preferredSize.height + searchField.preferredSize.height + JBUI.scale(12))

            // Listen to search field changes
            searchField.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    loadData(searchField.text)
                }
            })

            // Handle up/down keys in search field to navigate list
            searchField.textEditor.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_DOWN -> {
                            if (listModel.size() > 0) {
                                val currentIndex = list.selectedIndex
                                list.selectedIndex = if (currentIndex < listModel.size() - 1) currentIndex + 1 else 0
                                list.ensureIndexIsVisible(list.selectedIndex)
                                e.consume()
                            }
                        }

                        KeyEvent.VK_UP -> {
                            if (listModel.size() > 0) {
                                val currentIndex = list.selectedIndex
                                list.selectedIndex = if (currentIndex > 0) currentIndex - 1 else listModel.size() - 1
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
            })

            // Handle Enter key to select
            list.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && list.selectedValue != null) {
                        selectItem(list.selectedValue)
                    }
                }
            })

            // Handle double-click to select
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && list.selectedValue != null) {
                        selectItem(list.selectedValue)
                    }
                }
            })
        }

        /**
         * Load and filter data based on search query
         */
        fun loadData(query: String) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val items = buildItemList(project, vcs, query.trim())

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
    private class CompareItemRenderer : ColoredListCellRenderer<CompareItem>() {
        override fun customizeCellRenderer(
            list: JList<out CompareItem>,
            value: CompareItem?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            when (value) {
                is CompareItem.Bookmark -> {
                    icon = AllIcons.Vcs.Branch
                    append(value.item.bookmark.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(" (", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    val formatted = JujutsuCommitFormatter.format(value.item.changeId)
                    append(formatted.shortPart, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (formatted.restPart.isNotEmpty()) {
                        append(formatted.restPart, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    append(")", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }

                is CompareItem.Change -> {
                    icon = AllIcons.Vcs.CommitNode
                    val formatted = JujutsuCommitFormatter.format(value.changeId)
                    append(formatted.shortPart, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (formatted.restPart.isNotEmpty()) {
                        append(formatted.restPart, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    append(" ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    DescriptionRenderer.renderToComponent(value.description, ::append)
                }

                null -> {}
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
    private fun buildItemList(project: Project, vcs: JujutsuVcs, query: String): List<CompareItem> {
        val items = mutableListOf<CompareItem>()
        val cache = LogCache.getInstance(project)

        // Add bookmarks - always show all bookmarks filtered by query
        val bookmarkResult = vcs.logService.getBookmarks()
        if (bookmarkResult.isSuccess) {
            val bookmarks = bookmarkResult.getOrNull() ?: emptyList()

            // Filter bookmarks by query
            val filteredBookmarks = if (query.isEmpty()) {
                bookmarks
            } else {
                bookmarks.filter { bookmark ->
                    bookmark.bookmark.name.contains(query, ignoreCase = true) ||
                            bookmark.changeId.short.contains(query, ignoreCase = true)
                }
            }

            items.addAll(filteredBookmarks.map { CompareItem.Bookmark(it) })
        }

        // Add recent changes - limit to DEFAULT_LIMIT and filter by query
        // Try to get from cache first
        val entries = cache.get(Expression.ALL) ?: run {
            // Not in cache, fetch from jj and cache it
            val logResult = vcs.logService.getLogBasic(revset = Expression.ALL)
            logResult.getOrNull()?.also { fetchedEntries ->
                cache.put(Expression.ALL, emptyList(), fetchedEntries)
            } ?: emptyList()
        }

        // Filter changes by query
        val filteredEntries = if (query.isEmpty()) {
            entries.take(DEFAULT_LIMIT)
        } else {
            entries.filter { entry ->
                entry.changeId.short.contains(query, ignoreCase = true) ||
                        entry.description.display.contains(query, ignoreCase = true)
            }.take(DEFAULT_LIMIT)
        }

        // Convert to CompareItems
        filteredEntries.forEach { entry ->
            items.add(CompareItem.Change(entry))
        }

        return items
    }
}
