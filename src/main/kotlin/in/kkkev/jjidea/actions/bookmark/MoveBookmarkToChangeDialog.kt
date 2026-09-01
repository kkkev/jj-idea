package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.cli.TemplateParts
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.components.LogSearchField
import `in`.kkkev.jjidea.ui.components.TextCanvasPanel
import `in`.kkkev.jjidea.ui.components.appendSummary
import `in`.kkkev.jjidea.ui.components.icon
import `in`.kkkev.jjidea.ui.log.entryCanvas
import `in`.kkkev.jjidea.ui.log.fetchSearchResults
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.DefaultListModel
import javax.swing.DefaultListSelectionModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

private sealed class ChangeItem {
    data object EmptyState : ChangeItem()
    data class SectionHeader(val direction: MoveDirection) : ChangeItem()
    data class EntryRow(val entry: LogEntry, val direction: MoveDirection) : ChangeItem()
}

private class ChangeSectionHeaderPanel(text: String) : JPanel(GridBagLayout()) {
    init {
        isOpaque = false
        val leftGbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }
        val labelGbc = GridBagConstraints().apply { insets = JBUI.insets(0, 8) }
        val rightGbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }
        add(JSeparator(SwingConstants.HORIZONTAL), leftGbc)
        add(
            JBLabel(text).apply {
                foreground = UIUtil.getLabelDisabledForeground()
                font = font.deriveFont(Font.PLAIN, font.size - 1f)
                horizontalAlignment = SwingConstants.CENTER
            },
            labelGbc
        )
        add(JSeparator(SwingConstants.HORIZONTAL), rightGbc)
        border = JBUI.Borders.empty(4, 0)
    }
}

private class ChangeItemRenderer(private val checkbox: JBCheckBox) : ListCellRenderer<ChangeItem> {
    override fun getListCellRendererComponent(
        list: JList<out ChangeItem>,
        value: ChangeItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): JComponent = when (value) {
        is ChangeItem.EmptyState -> JBLabel(JujutsuBundle.message("dialog.bookmark.moveTo.empty")).apply {
            foreground = UIUtil.getLabelDisabledForeground()
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.empty(16, 8)
        }

        is ChangeItem.SectionHeader -> ChangeSectionHeaderPanel(
            when (value.direction) {
                MoveDirection.FORWARD -> JujutsuBundle.message("dialog.bookmark.moveTo.section.forward")
                MoveDirection.BACKWARD_OR_SIDEWAYS -> JujutsuBundle.message("dialog.bookmark.moveTo.section.backward")
            }
        )

        is ChangeItem.EntryRow -> {
            val isBackward = value.direction == MoveDirection.BACKWARD_OR_SIDEWAYS
            val disabled = isBackward && !checkbox.isSelected
            val panel = TextCanvasPanel()
            panel.background = if (isSelected && !disabled) list.selectionBackground else null
            panel.font = list.font

            val fg = when {
                disabled -> UIUtil.getLabelDisabledForeground()
                isSelected -> list.selectionForeground
                else -> list.foreground
            }
            val canvas = entryCanvas(value.entry, fg) {
                val dirIcon = if (isBackward) AllIcons.General::Warning else AllIcons.Actions::MoveUp
                append(icon(dirIcon))
                append(" ")
                appendSummary(value.entry)
            }
            panel.renderFrom(canvas)
            for (comp in panel.components) {
                comp.maximumSize = Dimension(comp.preferredSize.width, comp.maximumSize.height)
            }
            panel.add(Box.createHorizontalGlue())
            panel
        }
    }
}

class MoveBookmarkToChangeDialog(
    private val repo: JujutsuRepository,
    initialClassified: List<Pair<LogEntry, MoveDirection>>,
    /** The bookmark's current target, needed to classify any commit found by [searchWholeRepo]. */
    private val currentId: ChangeId?
) : DialogWrapper(repo.project) {
    data class Result(val changeId: ChangeId, val allowBackwards: Boolean)

    var result: Result? = null
        private set

    /** Grows via [searchWholeRepo] (jj-idea-tq4b) as off-window commits are found and classified. */
    private var classified: List<Pair<LogEntry, MoveDirection>> = initialClassified

    private val searchField = LogSearchField(
        placeholder = JujutsuBundle.message("dialog.bookmark.moveTo.search.emptytext"),
        onFilterChanged = { rebuildList() },
        onSubmitted = { searchWholeRepo() }
    )

    private val statusLabel = JBLabel().apply { isVisible = false }

    private val listModel = DefaultListModel<ChangeItem>()

    private val allowBackwardCheckbox = JBCheckBox(JujutsuBundle.message("dialog.bookmark.moveTo.allowBackward"))

    private val list = object : JBList<ChangeItem>(listModel) {
        override fun getScrollableTracksViewportWidth() = true
    }.apply {
        setSelectionModel(
            object : DefaultListSelectionModel() {
                override fun setSelectionInterval(i0: Int, i1: Int) {
                    if (isSelectableIndex(i1)) super.setSelectionInterval(i0, i1)
                }
            }.also { it.selectionMode = ListSelectionModel.SINGLE_SELECTION }
        )
        cellRenderer = ChangeItemRenderer(allowBackwardCheckbox)
        visibleRowCount = 12
    }

    init {
        title = JujutsuBundle.message("dialog.bookmark.moveTo.title")
        isOKActionEnabled = false
        init()

        rebuildList()

        // Up/Down navigate the list from the search field; Enter is handled by LogSearchField
        // itself (jj-idea-lpbv/jj-idea-tq4b: it now runs a whole-repo search, not dialog OK).
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        navigateList(1)
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        navigateList(-1)
                        e.consume()
                    }
                }
            }
        })

        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        navigateList(1)
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        navigateList(-1)
                        e.consume()
                    }
                }
            }
        })

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && isOKActionEnabled) doOKAction()
            }
        })

        list.addListSelectionListener { updateOkButton() }

        allowBackwardCheckbox.addItemListener {
            list.repaint()
            val sel = list.selectedValue
            if (sel is ChangeItem.EntryRow &&
                sel.direction == MoveDirection.BACKWARD_OR_SIDEWAYS &&
                !allowBackwardCheckbox.isSelected
            ) {
                list.clearSelection()
            }
            updateOkButton()
        }
    }

    override fun createCenterPanel(): JComponent {
        val scrollPane = JBScrollPane(list).apply { border = JBUI.Borders.empty() }
        val checkboxPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 4, 0, 4)
            add(allowBackwardCheckbox, BorderLayout.WEST)
        }
        val topPanel = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(500), JBUI.scale(380))
            add(topPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(checkboxPanel, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent() = searchField

    override fun doOKAction() {
        val row = list.selectedValue as? ChangeItem.EntryRow ?: return
        result = Result(
            changeId = row.entry.id,
            allowBackwards = row.direction == MoveDirection.BACKWARD_OR_SIDEWAYS
        )
        super.doOKAction()
    }

    private fun rebuildList() {
        val matcher = searchField.matcher()
        val filtered = if (matcher == null) classified else classified.filter { matcher.matches(it.first) }

        val forwards = filtered.filter { it.second == MoveDirection.FORWARD }
        val backwards = filtered.filter { it.second == MoveDirection.BACKWARD_OR_SIDEWAYS }

        listModel.clear()
        if (filtered.isEmpty()) {
            listModel.addElement(ChangeItem.EmptyState)
        } else {
            if (forwards.isNotEmpty()) {
                listModel.addElement(ChangeItem.SectionHeader(MoveDirection.FORWARD))
                forwards.forEach { listModel.addElement(ChangeItem.EntryRow(it.first, it.second)) }
            }
            if (backwards.isNotEmpty()) {
                listModel.addElement(ChangeItem.SectionHeader(MoveDirection.BACKWARD_OR_SIDEWAYS))
                backwards.forEach { listModel.addElement(ChangeItem.EntryRow(it.first, it.second)) }
            }
        }

        selectFirstSelectable()
    }

    /**
     * Whole-repo search (jj-idea-lpbv/jj-idea-tq4b): runs the search revset against [repo],
     * classifies any newly found commits against [currentId] (the same rule [loadData] uses),
     * and merges them into [classified] so they become pickable even though they were outside
     * the loaded log window.
     */
    private fun searchWholeRepo() {
        val revset = searchField.revset() ?: return
        val settings = JujutsuSettings.getInstance(repo.project)
        runInBackground(ModalityState.any()) {
            val found = fetchSearchResults(listOf(repo), revset) { settings.logChangeLimit(it) }[repo] ?: emptyList()
            val alreadyKnown = classified.mapTo(mutableSetOf()) { it.first.id } + listOfNotNull(currentId)
            val newEntries = found.filter { it.id !in alreadyKnown }
            if (newEntries.isNotEmpty()) repo.logCache.store(newEntries)
            val newlyClassified = classifyAgainstBookmark(repo, currentId, newEntries)
            runLater {
                if (isDisposed) return@runLater
                if (newlyClassified.isNotEmpty()) {
                    classified = classified + newlyClassified
                    rebuildList()
                }
                statusLabel.text = if (newlyClassified.isNotEmpty()) {
                    JujutsuBundle.message("log.status.search.found", newlyClassified.size, searchField.text)
                } else {
                    JujutsuBundle.message("log.status.search.none", searchField.text)
                }
                statusLabel.isVisible = true
            }
        }
    }

    private fun selectFirstSelectable() {
        for (i in 0 until listModel.size()) {
            if (isSelectableIndex(i)) {
                list.selectedIndex = i
                list.ensureIndexIsVisible(i)
                return
            }
        }
    }

    private fun isSelectableIndex(index: Int): Boolean {
        if (index < 0 || index >= listModel.size()) return false
        return when (val item = listModel.getElementAt(index)) {
            is ChangeItem.EntryRow -> item.direction == MoveDirection.FORWARD || allowBackwardCheckbox.isSelected
            else -> false
        }
    }

    private fun navigateList(delta: Int) {
        if (listModel.size() == 0) return
        var idx = list.selectedIndex
        var steps = listModel.size()
        while (steps-- > 0) {
            idx = when {
                idx + delta < 0 -> listModel.size() - 1
                idx + delta >= listModel.size() -> 0
                else -> idx + delta
            }
            if (isSelectableIndex(idx)) {
                list.selectedIndex = idx
                list.ensureIndexIsVisible(idx)
                return
            }
        }
    }

    private fun updateOkButton() {
        val sel = list.selectedValue
        isOKActionEnabled = sel is ChangeItem.EntryRow &&
            (sel.direction == MoveDirection.FORWARD || allowBackwardCheckbox.isSelected)
    }

    companion object {
        private val log = Logger.getInstance(MoveBookmarkToChangeDialog::class.java)

        fun show(repo: JujutsuRepository, bookmark: Bookmark, onSelected: (ChangeId, Boolean) -> Unit) {
            runInBackground {
                val currentId = currentBookmarkTarget(repo, bookmark)
                val entries = repo.logCache.all
                val classified = classifyAgainstBookmark(repo, currentId, entries.filter { it.id != currentId })
                runLater {
                    val dlg = MoveBookmarkToChangeDialog(repo, classified, currentId)
                    if (dlg.showAndGet()) {
                        val r = dlg.result ?: return@runLater
                        onSelected(r.changeId, r.allowBackwards)
                    }
                }
            }
        }

        /** The bookmark's current target change id, or null if the bookmark doesn't exist yet. */
        private fun currentBookmarkTarget(repo: JujutsuRepository, bookmark: Bookmark): ChangeId? =
            repo.logService.getBookmarks().getOrNull()?.find { it.bookmark.name == bookmark.name }?.id

        /**
         * Classifies [candidates] against [currentId]: descendants of the bookmark's current
         * target → FORWARD (the bookmark would advance); everything else → BACKWARD_OR_SIDEWAYS.
         * Shared by [loadData] (the initial load) and [searchWholeRepo] (jj-idea-tq4b, classifying
         * commits found outside the loaded log window).
         */
        private fun classifyAgainstBookmark(
            repo: JujutsuRepository,
            currentId: ChangeId?,
            candidates: List<LogEntry>
        ): List<Pair<LogEntry, MoveDirection>> {
            if (candidates.isEmpty()) return emptyList()
            if (currentId == null) {
                return candidates.map { it to MoveDirection.BACKWARD_OR_SIDEWAYS }
            }

            // Query descendants of the bookmark's current target only — candidate ids are never put on the
            // command line, so this is O(1) in the number of candidates and immune to any one candidate being an
            // unresolvable/divergent id.
            val revset = BookmarkClassifier.descendantRevset(currentId)
            val result = repo.commandExecutor.log(
                revset = revset,
                template = "${TemplateParts.changeIdWithOffset()} ++ \"\\n\""
            )
            val forwardIds = if (result.isSuccess) {
                result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            } else {
                log.warn("Descendant revset query '$revset' failed: ${result.stderr}")
                emptySet()
            }

            return candidates.map { entry ->
                val direction = if (entry.id.full in forwardIds) {
                    MoveDirection.FORWARD
                } else {
                    MoveDirection.BACKWARD_OR_SIDEWAYS
                }
                entry to direction
            }
        }

        fun loadData(repo: JujutsuRepository, bookmark: Bookmark): List<Pair<LogEntry, MoveDirection>> {
            val entries = repo.logCache.all
            if (entries.isEmpty()) return emptyList()

            // Exclude the entry the bookmark is currently on (no point in "moving" there)
            val currentId = currentBookmarkTarget(repo, bookmark)
            val candidates = entries.filter { it.id != currentId }
            return classifyAgainstBookmark(repo, currentId, candidates)
        }
    }
}
