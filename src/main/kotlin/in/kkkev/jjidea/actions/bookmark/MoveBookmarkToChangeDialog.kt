package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.components.TextCanvasPanel
import `in`.kkkev.jjidea.ui.components.appendSummary
import `in`.kkkev.jjidea.ui.components.icon
import `in`.kkkev.jjidea.ui.log.entryCanvas
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
import javax.swing.event.DocumentEvent

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
    private val classified: List<Pair<LogEntry, MoveDirection>>
) : DialogWrapper(repo.project) {
    data class Result(val changeId: ChangeId, val allowBackwards: Boolean)

    var result: Result? = null
        private set

    private var query = ""

    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = JujutsuBundle.message("dialog.bookmark.moveTo.search.emptytext")
    }

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

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                query = searchField.text.trim()
                rebuildList()
            }
        })

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
                    KeyEvent.VK_ENTER -> {
                        if (isOKActionEnabled) {
                            doOKAction()
                            e.consume()
                        }
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
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(500), JBUI.scale(380))
            add(searchField, BorderLayout.NORTH)
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
        val filtered = if (query.isEmpty()) {
            classified
        } else {
            classified.filter {
                it.first.id.shortenable.full.contains(query, ignoreCase = true) ||
                    it.first.description.summary.contains(query, ignoreCase = true)
            }
        }

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
                val classified = loadData(repo, bookmark)
                runLater {
                    val dlg = MoveBookmarkToChangeDialog(repo, classified)
                    if (dlg.showAndGet()) {
                        val r = dlg.result ?: return@runLater
                        onSelected(r.changeId, r.allowBackwards)
                    }
                }
            }
        }

        fun loadData(repo: JujutsuRepository, bookmark: Bookmark): List<Pair<LogEntry, MoveDirection>> {
            val settings = JujutsuSettings.getInstance(repo.project)
            val limit = settings.logChangeLimit(repo)
            val entries = repo.logService.getLog(limit = limit).getOrElse {
                log.warn("Failed to load log for move-to-change dialog", it)
                return emptyList()
            }

            // Exclude the entry the bookmark is currently on (no point in "moving" there)
            val currentId = repo.logService.getBookmarks().getOrNull()
                ?.find { it.bookmark.name == bookmark.name }?.id

            val candidates = entries.filter { it.id != currentId }
            if (candidates.isEmpty()) return emptyList()

            // Classify: entries that are descendants of the current bookmark target → FORWARD
            // (the bookmark would advance). Ancestors → BACKWARD_OR_SIDEWAYS.
            if (currentId == null) {
                return candidates.map { it to MoveDirection.BACKWARD_OR_SIDEWAYS }
            }

            val ids = candidates.joinToString(" | ") { it.id.shortenable.full }
            val revset = Expression("($ids) & ${currentId.shortenable.full}::")
            val result = repo.commandExecutor.log(revset = revset, template = "change_id ++ \"\\n\"")
            val forwardIds = if (result.isSuccess) {
                result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            } else {
                log.warn("Descendant revset query failed: ${result.stderr}")
                emptySet()
            }

            return candidates.map { entry ->
                val direction = if (entry.id.shortenable.full in forwardIds) {
                    MoveDirection.FORWARD
                } else {
                    MoveDirection.BACKWARD_OR_SIDEWAYS
                }
                entry to direction
            }
        }
    }
}
