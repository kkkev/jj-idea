package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.LogCache
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
            val changeId: String,
            val description: String,
            override val displayName: String = "$changeId - ${description.take(60)}",
            override val revision: String = changeId
        ) : CompareItem(displayName, revision)

        /** Named bookmark */
        data class Bookmark(
            val name: String,
            val changeId: String,
            override val displayName: String = "$name ($changeId)",
            override val revision: String = name
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
        private val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = CompareItemRenderer()
        }

        private var currentPopup: JBPopup? = null

        init {
            // Search field
            add(searchField, BorderLayout.NORTH)

            // Results list
            val scrollPane = JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(500, 400)
            }
            add(scrollPane, BorderLayout.CENTER)

            // Listen to search field changes
            searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: javax.swing.event.DocumentEvent) {
                    loadData(searchField.text)
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
            // Find and close the popup
            val parent = this.parent
            if (parent != null) {
                var component = parent
                while (component != null) {
                    if (component is com.intellij.ui.popup.AbstractPopup) {
                        component.cancel()
                        break
                    }
                    component = component.parent
                }
            }
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
                    append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(" (${value.changeId})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is CompareItem.Change -> {
                    icon = AllIcons.Vcs.CommitNode
                    append(value.changeId, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(" - ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    val desc = value.description.lines().firstOrNull()?.take(60) ?: "(no description)"
                    append(desc, SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
        val bookmarkResult = vcs.commandExecutor.bookmarkList()
        if (bookmarkResult.isSuccess) {
            val bookmarkLines = bookmarkResult.stdout.lines().filter { it.isNotBlank() && it.contains(':') }
            val bookmarks = bookmarkLines.mapNotNull { line ->
                // Format: "bookmark-name: change-id"
                val parts = line.split(':', limit = 2)
                if (parts.size == 2) {
                    val bookmarkName = parts[0].trim()
                    val changeId = parts[1].trim().take(12)
                    if (bookmarkName.isNotEmpty()) {
                        CompareItem.Bookmark(bookmarkName, changeId)
                    } else null
                } else null
            }

            // Filter bookmarks by query
            val filteredBookmarks = if (query.isEmpty()) {
                bookmarks
            } else {
                bookmarks.filter { bookmark ->
                    bookmark.name.contains(query, ignoreCase = true) ||
                            bookmark.changeId.contains(query, ignoreCase = true)
                }
            }

            items.addAll(filteredBookmarks)
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
                val changeId = entry.changeId.short
                val description = entry.description
                changeId.contains(query, ignoreCase = true) ||
                        description.contains(query, ignoreCase = true)
            }.take(DEFAULT_LIMIT)
        }

        // Convert to CompareItems
        filteredEntries.forEach { entry ->
            val changeId = entry.changeId.short
            val description = entry.description.trim().ifEmpty { "(no description)" }
            items.add(CompareItem.Change(changeId, description))
        }

        return items
    }
}
