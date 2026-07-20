package `in`.kkkev.jjidea.ui.duplicate

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.components.*
import `in`.kkkev.jjidea.ui.log.*
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.DocumentEvent

/**
 * Result of the duplicate dialog — the user's chosen destination and placement.
 */
data class DuplicateSpec(
    val destinations: List<Revision>,
    val destinationMode: RebaseDestinationMode
)

/**
 * Dialog for configuring the destination of a `jj duplicate` operation.
 *
 * Unlike [in.kkkev.jjidea.ui.rebase.RebaseDialog], there is no source-mode concept
 * (duplicate's revisions are always positional) and no preview panel; this dialog only
 * picks a destination and placement (onto/after/before).
 */
class DuplicateDialog(
    private val project: Project,
    private val repo: JujutsuRepository,
    private val sourceEntries: List<LogEntry>
) : DialogWrapper(project) {
    var result: DuplicateSpec? = null
        private set

    private var repoEntries: List<LogEntry> = emptyList()

    /**
     * True while [loadDestinations] or a programmatic mode change is in progress, so the
     * placement-radio and destination-table listeners don't re-trigger each other (e.g.
     * restoring several row selections fires one selection event per row).
     */
    private var updating = false

    private val destModeOnto = JRadioButton(JujutsuBundle.message("dialog.rebase.placement.onto")).apply {
        toolTipText = JujutsuBundle.message("dialog.rebase.placement.onto.description")
        isSelected = true
    }
    private val destModeAfter = JRadioButton(JujutsuBundle.message("dialog.rebase.placement.after")).apply {
        toolTipText = JujutsuBundle.message("dialog.rebase.placement.after.description")
    }
    private val destModeBefore = JRadioButton(JujutsuBundle.message("dialog.rebase.placement.before")).apply {
        toolTipText = JujutsuBundle.message("dialog.rebase.placement.before.description")
    }

    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = JujutsuBundle.message("dialog.duplicate.destination.search")
    }
    private val destTableModel = JujutsuLogTableModel()
    private var destGraphNodes: Map<ChangeId, GraphNode> = emptyMap()
    private val destinationTable = JBTable(destTableModel).apply {
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        tableHeader.isVisible = false
        rowHeight = JBUI.scale(22)
        setStriped(true)
    }

    init {
        title = JujutsuBundle.message("dialog.duplicate.title")
        setOKButtonText(JujutsuBundle.message("dialog.duplicate.button"))

        ButtonGroup().apply {
            add(destModeOnto)
            add(destModeAfter)
            add(destModeBefore)
        }

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = loadDestinations(searchField.text)
        })

        // Re-filter destinations when placement mode changes (invalid set depends on mode)
        val onModeChanged = { if (!updating) loadDestinations(searchField.text) }
        destModeOnto.addActionListener { onModeChanged() }
        destModeAfter.addActionListener { onModeChanged() }
        destModeBefore.addActionListener { onModeChanged() }

        // Disable placement modes that the current destination selection can't support
        destinationTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !updating) updateModeAvailability()
        }

        init()

        hideExtraColumns()
        updateDestRenderer()

        runInBackground(ModalityState.any()) {
            val entries = repo.logCache.all
            runLater {
                if (!isDisposed) {
                    repoEntries = entries
                    loadDestinations("")
                }
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val topSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(createSectionLabel(JujutsuBundle.message("dialog.duplicate.source")))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(createSourcePanel())
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(JSeparator().apply { alignmentX = JPanel.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(createSectionLabel(JujutsuBundle.message("dialog.duplicate.destination")))
            add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        val bottomSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createSectionLabel(JujutsuBundle.message("dialog.duplicate.placement")))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(createPlacementModePanel())
        }

        val wrapper = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(topSection, BorderLayout.NORTH)
            add(createDestinationPanel(), BorderLayout.CENTER)
            add(bottomSection, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(550), JBUI.scale(500))
        }
        return wrapper
    }

    private fun createSectionLabel(text: String): JLabel {
        val label = JLabel(text)
        label.font = label.font.deriveFont(java.awt.Font.BOLD)
        label.alignmentX = JLabel.LEFT_ALIGNMENT
        return label
    }

    private fun createSourcePanel() = IconAwareHtmlPane(project).apply {
        alignmentX = JPanel.LEFT_ALIGNMENT
        text = htmlString {
            append(sourceEntries, separator = "\n") { entry ->
                appendStatusIndicators(entry)
                append(entry.id)
                append(" ")
                appendDescriptionAndEmptyIndicator(entry)
                append(" ")
                appendDecorations(entry)
            }
        }
    }

    private fun createDestinationPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.alignmentX = JPanel.LEFT_ALIGNMENT

        searchField.alignmentX = JPanel.LEFT_ALIGNMENT
        panel.add(searchField, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(destinationTable).apply {
            border = JBUI.Borders.empty()
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun createPlacementModePanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = JPanel.LEFT_ALIGNMENT
        panel.border = JBUI.Borders.empty(0, 8)
        panel.add(destModeOnto)
        panel.add(destModeAfter)
        panel.add(destModeBefore)
        return panel
    }

    private fun loadDestinations(query: String) {
        val trimmed = query.trim()
        val sourceIds = sourceEntries.map { it.id }.toSet()
        // Destinations that would make jj rewrite an immutable commit under the current
        // placement mode (see DuplicateImmutabilityGuard) — never offered as a target.
        val invalid = invalidDestinationIds(repoEntries, selectedDestinationMode)

        val matchesSearch = { entry: LogEntry ->
            trimmed.isEmpty() ||
                entry.id.short.contains(trimmed, ignoreCase = true) ||
                entry.id.full.contains(trimmed, ignoreCase = true) ||
                entry.description.display.contains(trimmed, ignoreCase = true) ||
                entry.bookmarks.any { it.name.name.contains(trimmed, ignoreCase = true) }
        }
        // Unlike rebase, duplicating onto a descendant (or even a source itself, once
        // it's a copy) is meaningful, so beyond the immutability guard above, the only
        // revisions excluded from the picker are the sources themselves.
        val filtered = repoEntries.filter { it.id !in sourceIds && it.id !in invalid && matchesSearch(it) }

        val previousSelections = selectedDestinationIds() - invalid

        updating = true
        try {
            destTableModel.setEntries(filtered)
            destGraphNodes = CommitGraphBuilder().buildGraph(filtered)
            updateDestRenderer()

            for (i in 0 until destTableModel.rowCount) {
                val entry = destTableModel.getEntry(i)
                if (entry != null && entry.id in previousSelections) {
                    destinationTable.addRowSelectionInterval(i, i)
                }
            }
        } finally {
            updating = false
        }

        updateModeAvailability()
    }

    /**
     * Disable placement modes the current destination selection can't support (see
     * [validPlacementModes]), falling back to Onto — always valid — if the mode that's
     * currently selected just became unavailable.
     */
    private fun updateModeAvailability() {
        val valid = validPlacementModes(repoEntries, selectedDestinationIds())
        destModeAfter.isEnabled = RebaseDestinationMode.INSERT_AFTER in valid
        destModeBefore.isEnabled = RebaseDestinationMode.INSERT_BEFORE in valid

        if (selectedDestinationMode !in valid) {
            updating = true
            try {
                destModeOnto.isSelected = true
            } finally {
                updating = false
            }
            loadDestinations(searchField.text)
        }
    }

    private fun hideExtraColumns() {
        val columnsToRemove = (destinationTable.columnCount - 1 downTo 0)
            .filter { it != JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION }

        for (colIndex in columnsToRemove) {
            if (colIndex < destinationTable.columnModel.columnCount) {
                destinationTable.removeColumn(destinationTable.columnModel.getColumn(colIndex))
            }
        }
    }

    private fun updateDestRenderer() {
        for (i in 0 until destinationTable.columnModel.columnCount) {
            val column = destinationTable.columnModel.getColumn(i)
            if (column.modelIndex == JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION) {
                column.cellRenderer = JujutsuGraphAndDescriptionRenderer(destGraphNodes)
                break
            }
        }
    }

    private fun selectedDestinationIds(): Set<ChangeId> =
        destinationTable.selectedRows.toList()
            .mapNotNull { destTableModel.getEntry(it) }
            .map { it.id }
            .toSet()

    private fun selectedDestinationEntries(): List<LogEntry> =
        destinationTable.selectedRows.toList().mapNotNull { destTableModel.getEntry(it) }

    private val selectedDestinationMode: RebaseDestinationMode
        get() = when {
            destModeOnto.isSelected -> RebaseDestinationMode.ONTO
            destModeAfter.isSelected -> RebaseDestinationMode.INSERT_AFTER
            else -> RebaseDestinationMode.INSERT_BEFORE
        }

    override fun doValidate(): ValidationInfo? {
        if (selectedDestinationIds().isEmpty()) {
            return ValidationInfo(JujutsuBundle.message("dialog.duplicate.destination.none"), destinationTable)
        }
        return null
    }

    override fun doOKAction() {
        val destinations = selectedDestinationEntries().map { it.id as Revision }

        result = DuplicateSpec(
            destinations = destinations,
            destinationMode = selectedDestinationMode
        )
        super.doOKAction()
    }
}
