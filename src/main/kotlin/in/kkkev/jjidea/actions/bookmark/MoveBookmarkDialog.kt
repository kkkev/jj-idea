package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
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
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.ui.components.FragmentRecordingCanvas
import `in`.kkkev.jjidea.ui.components.TextCanvasPanel
import `in`.kkkev.jjidea.ui.components.append
import `in`.kkkev.jjidea.ui.components.icon
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
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

private sealed class Item {
    data object EmptyState : Item()
    data class SectionHeader(val direction: MoveDirection) : Item()
    data class BookmarkRow(val classified: ClassifiedBookmark) : Item()
}

private class AlphaTextCanvasPanel : TextCanvasPanel() {
    init {
        isOpaque = false
    }

    override fun paintChildren(g: Graphics) {
        val g2 = g as Graphics2D
        val orig = g2.composite
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
        super.paintChildren(g)
        g2.composite = orig
    }
}

private class SectionHeaderPanel(text: String) : JPanel(GridBagLayout()) {
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

private class ItemRenderer(private val checkbox: JBCheckBox) : ListCellRenderer<Item> {
    private val conflictTag = "  ${JujutsuBundle.message("dialog.bookmark.move.tooltip.conflict.tag")}"

    override fun getListCellRendererComponent(
        list: JList<out Item>,
        value: Item,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): JComponent = when (value) {
        is Item.EmptyState -> JBLabel(JujutsuBundle.message("dialog.bookmark.move.empty")).apply {
            foreground = UIUtil.getLabelDisabledForeground()
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.empty(16, 8)
        }

        is Item.SectionHeader -> SectionHeaderPanel(
            when (value.direction) {
                MoveDirection.FORWARD -> JujutsuBundle.message("dialog.bookmark.move.section.forward")
                MoveDirection.BACKWARD_OR_SIDEWAYS ->
                    JujutsuBundle.message("dialog.bookmark.move.section.backward")
            }
        )

        is Item.BookmarkRow -> {
            val classified = value.classified
            val isBackward = classified.direction == MoveDirection.BACKWARD_OR_SIDEWAYS
            val disabled = isBackward && !checkbox.isSelected
            val panel = if (disabled) AlphaTextCanvasPanel() else TextCanvasPanel()
            panel.background = if (isSelected && !disabled) list.selectionBackground else null
            panel.font = list.font

            val canvas = FragmentRecordingCanvas()
            val fg = if (isSelected && !disabled) list.selectionForeground else list.foreground
            canvas.foreground(fg) {
                val dirIcon = if (isBackward) AllIcons.General::Warning else AllIcons.Actions::MoveUp
                append(icon(dirIcon))
                append(" ")
                append(classified.item.bookmark)
                classified.item.id?.let { id ->
                    append("  ")
                    append(id)
                }
                if (classified.item.bookmark.conflict) {
                    grey { italic { append(conflictTag) } }
                }
            }
            panel.renderFrom(canvas)
            // Cap each child's max width to its preferred width so BoxLayout doesn't distribute
            // extra horizontal space into the content, which would centre it in the cell.
            // Only the horizontal glue (added below) gets the extra space.
            for (comp in panel.components) {
                comp.maximumSize = java.awt.Dimension(comp.preferredSize.width, comp.maximumSize.height)
            }
            panel.add(Box.createHorizontalGlue())
            panel
        }
    }
}

class MoveBookmarkDialog(
    project: Project,
    private val classified: List<ClassifiedBookmark>
) : DialogWrapper(project) {
    data class Result(val bookmark: Bookmark, val allowBackwards: Boolean)

    var result: Result? = null
        private set

    private var query: String = ""

    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = JujutsuBundle.message("dialog.bookmark.move.search.emptytext")
    }

    private val listModel = DefaultListModel<Item>()

    private val allowBackwardCheckbox =
        JBCheckBox(JujutsuBundle.message("dialog.bookmark.move.allow.backward"))

    private val list = object : JBList<Item>(listModel) {
        override fun getScrollableTracksViewportWidth() = true
    }.apply {
        setSelectionModel(
            object : DefaultListSelectionModel() {
                override fun setSelectionInterval(i0: Int, i1: Int) {
                    if (isSelectableIndex(i1)) super.setSelectionInterval(i0, i1)
                }
            }.also { it.selectionMode = ListSelectionModel.SINGLE_SELECTION }
        )
        cellRenderer = ItemRenderer(allowBackwardCheckbox)
        visibleRowCount = 12
    }

    init {
        title = JujutsuBundle.message("dialog.bookmark.move.title")
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
            if (sel is Item.BookmarkRow &&
                sel.classified.direction == MoveDirection.BACKWARD_OR_SIDEWAYS &&
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
            preferredSize = Dimension(JBUI.scale(450), JBUI.scale(360))
            add(searchField, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(checkboxPanel, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent() = searchField

    override fun doOKAction() {
        val row = list.selectedValue as? Item.BookmarkRow ?: return
        result = Result(
            bookmark = row.classified.item.bookmark,
            allowBackwards = row.classified.direction == MoveDirection.BACKWARD_OR_SIDEWAYS
        )
        super.doOKAction()
    }

    private fun rebuildList() {
        val filtered = if (query.isEmpty()) {
            classified
        } else {
            classified.filter { it.item.bookmark.name.contains(query, ignoreCase = true) }
        }

        val forwards = filtered.filter { it.direction == MoveDirection.FORWARD }
        val backwards = filtered.filter { it.direction == MoveDirection.BACKWARD_OR_SIDEWAYS }

        listModel.clear()
        if (filtered.isEmpty()) {
            listModel.addElement(Item.EmptyState)
        } else {
            if (forwards.isNotEmpty()) {
                listModel.addElement(Item.SectionHeader(MoveDirection.FORWARD))
                forwards.forEach { listModel.addElement(Item.BookmarkRow(it)) }
            }
            if (backwards.isNotEmpty()) {
                listModel.addElement(Item.SectionHeader(MoveDirection.BACKWARD_OR_SIDEWAYS))
                backwards.forEach { listModel.addElement(Item.BookmarkRow(it)) }
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
            is Item.BookmarkRow ->
                item.classified.direction == MoveDirection.FORWARD || allowBackwardCheckbox.isSelected
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
        isOKActionEnabled = sel is Item.BookmarkRow &&
            (sel.classified.direction == MoveDirection.FORWARD || allowBackwardCheckbox.isSelected)
    }

    companion object {
        private val log = Logger.getInstance(MoveBookmarkDialog::class.java)

        fun show(repo: JujutsuRepository, targetId: ChangeId, onSelected: (Bookmark, Boolean) -> Unit) {
            runInBackground {
                val classified = loadData(repo, targetId)
                runLater {
                    val dlg = MoveBookmarkDialog(repo.project, classified)
                    if (dlg.showAndGet()) {
                        val r = dlg.result ?: return@runLater
                        onSelected(r.bookmark, r.allowBackwards)
                    }
                }
            }
        }

        fun loadData(repo: JujutsuRepository, targetId: ChangeId): List<ClassifiedBookmark> {
            val bookmarks = repo.logService.getBookmarks().getOrElse {
                log.warn("Failed to load bookmarks for move dialog", it)
                return emptyList()
            }
            val candidates = BookmarkClassifier.eligible(bookmarks, targetId)
            val revset = BookmarkClassifier.ancestorRevset(candidates, targetId)
                ?: return candidates.map { ClassifiedBookmark(it, MoveDirection.BACKWARD_OR_SIDEWAYS) }

            val result = repo.commandExecutor.log(revset = revset, template = "change_id ++ \"\\n\"")
            val forwardIds = if (result.isSuccess) {
                result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            } else {
                log.warn("Ancestor revset query failed: ${result.stderr}")
                emptySet()
            }
            return BookmarkClassifier.classify(candidates, forwardIds)
        }
    }
}
