package `in`.kkkev.jjidea.ui.rebase

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.components.*
import `in`.kkkev.jjidea.ui.log.*
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.DocumentEvent

/**
 * Result of the rebase dialog — the user's chosen parameters.
 */
data class RebaseSpec(
    val revisions: List<Revision>,
    val destinations: List<Revision>,
    val sourceMode: RebaseSourceMode,
    val destinationMode: RebaseDestinationMode
)

/**
 * Dialog for configuring a `jj rebase` operation.
 *
 * Left side: source section, source mode, destination table (log-style with graph), placement mode.
 * Right side: simulated post-rebase preview graph.
 */
class RebaseDialog(
    private val project: Project,
    private val repo: JujutsuRepository,
    private val sourceEntries: List<LogEntry>,
    allEntries: List<LogEntry> = emptyList()
) : DialogWrapper(project) {
    var result: RebaseSpec? = null
        private set

    private val repoEntries = allEntries.filter { it.repo == repo }

    // Source mode radio buttons
    private val sourceModeRevision = JRadioButton(JujutsuBundle.message("dialog.rebase.source.mode.revision")).apply {
        toolTipText = JujutsuBundle.message("dialog.rebase.source.mode.revision.description")
        isSelected = sourceEntries.size > 1
    }
    private val sourceModeSource = JRadioButton(JujutsuBundle.message("dialog.rebase.source.mode.source")).apply {
        toolTipText = JujutsuBundle.message("dialog.rebase.source.mode.source.description")
        isSelected = sourceEntries.size == 1
    }
    private val sourceModeBranch = JRadioButton(JujutsuBundle.message("dialog.rebase.source.mode.branch")).apply {
        toolTipText = JujutsuBundle.message("dialog.rebase.source.mode.branch.description")
    }

    // Destination mode radio buttons
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

    // Destination picker — log-style table
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = JujutsuBundle.message("dialog.rebase.destination.search")
    }
    private val destTableModel = JujutsuLogTableModel()
    private var destGraphNodes: Map<ChangeId, GraphNode> = emptyMap()
    private val destinationTable = JBTable(destTableModel).apply {
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        tableHeader.isVisible = false
        rowHeight = JBUI.scale(22)
        setStriped(true)
    }

    // Preview
    private val previewPanel = RebasePreviewPanel()

    init {
        title = JujutsuBundle.message("dialog.rebase.title")
        setOKButtonText(JujutsuBundle.message("dialog.rebase.button"))

        ButtonGroup().apply {
            add(sourceModeRevision)
            add(sourceModeSource)
            add(sourceModeBranch)
        }
        ButtonGroup().apply {
            add(destModeOnto)
            add(destModeAfter)
            add(destModeBefore)
        }

        // Listen to search changes
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = loadDestinations(searchField.text)
        })

        // Update preview when destination selection or placement mode changes
        destinationTable.selectionModel.addListSelectionListener { updatePreviewPanel() }
        destModeOnto.addActionListener { updatePreviewPanel() }
        destModeAfter.addActionListener { updatePreviewPanel() }
        destModeBefore.addActionListener { updatePreviewPanel() }

        // Re-filter destinations when source mode changes (excluded set depends on mode)
        sourceModeRevision.addActionListener { loadDestinations(searchField.text) }
        sourceModeSource.addActionListener { loadDestinations(searchField.text) }
        sourceModeBranch.addActionListener { loadDestinations(searchField.text) }

        init()

        // Populate destination table from pre-loaded entries
        previewPanel.setEntries(repoEntries)
        loadDestinations("")
        // Hide extra columns once after initial population — setEntries doesn't recreate columns
        hideExtraColumns()
        updateDestRenderer()
    }

    override fun createCenterPanel(): JComponent {
        // Fixed-height top section: source info + source mode
        val topSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(createSectionLabel(JujutsuBundle.message("dialog.rebase.source")))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(createSourcePanel())
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createSectionLabel(JujutsuBundle.message("dialog.rebase.source.mode")))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(createSourceModePanel())
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(JSeparator().apply { alignmentX = JPanel.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(createSectionLabel(JujutsuBundle.message("dialog.rebase.destination")))
            add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        // Fixed-height bottom section: placement mode
        val bottomSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createSectionLabel(JujutsuBundle.message("dialog.rebase.placement")))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(createPlacementModePanel())
        }

        // Left panel: top fixed, destination fills center, bottom fixed
        val leftWrapper = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(topSection, BorderLayout.NORTH)
            add(createDestinationPanel(), BorderLayout.CENTER)
            add(bottomSection, BorderLayout.SOUTH)
        }

        // Right panel: preview
        val rightPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(createSectionLabel(JujutsuBundle.message("dialog.rebase.preview")), BorderLayout.NORTH)
            add(previewPanel, BorderLayout.CENTER)
        }

        val splitter = OnePixelSplitter(false, 0.5f).apply {
            firstComponent = leftWrapper
            secondComponent = rightPanel
        }

        val wrapper = JPanel(BorderLayout())
        wrapper.add(splitter, BorderLayout.CENTER)
        wrapper.preferredSize = Dimension(JBUI.scale(1100), JBUI.scale(650))
        return wrapper
    }

    private fun createSectionLabel(text: String): JLabel {
        val label = JLabel(text)
        label.font = label.font.deriveFont(java.awt.Font.BOLD)
        label.alignmentX = JLabel.LEFT_ALIGNMENT
        return label
    }

    private fun createSourcePanel() = IconAwareHtmlPane().apply {
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

    private fun createSourceModePanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = JPanel.LEFT_ALIGNMENT
        panel.border = JBUI.Borders.empty(0, 8)
        panel.add(sourceModeRevision)
        panel.add(sourceModeSource)
        panel.add(sourceModeBranch)
        return panel
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
        val excluded = RebaseSimulator.excludedDestinationIds(repoEntries, sourceIds, selectedSourceMode)

        val matchesSearch = { entry: LogEntry ->
            trimmed.isEmpty() ||
                entry.id.short.contains(trimmed, ignoreCase = true) ||
                entry.id.full.contains(trimmed, ignoreCase = true) ||
                entry.description.display.contains(trimmed, ignoreCase = true) ||
                entry.bookmarks.any { it.name.contains(trimmed, ignoreCase = true) }
        }
        val filtered = repoEntries.filter { it.id !in excluded && matchesSearch(it) }

        // Remember previous selections (drop any that are now excluded)
        val previousSelections = selectedDestinationIds() - excluded

        destTableModel.setEntries(filtered)
        destGraphNodes = CommitGraphBuilder().buildGraph(filtered)
        updateDestRenderer()

        // Restore previous selections
        for (i in 0 until destTableModel.rowCount) {
            val entry = destTableModel.getEntry(i)
            if (entry != null && entry.id in previousSelections) {
                destinationTable.addRowSelectionInterval(i, i)
            }
        }

        updatePreviewPanel()
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

    private fun updatePreviewPanel() {
        val destIds = selectedDestinationIds()

        previewPanel.update(
            sourceEntries = sourceEntries,
            destinationIds = destIds,
            sourceMode = selectedSourceMode,
            destinationMode = selectedDestinationMode
        )
    }

    private val selectedSourceMode: RebaseSourceMode
        get() = when {
            sourceModeRevision.isSelected -> RebaseSourceMode.REVISION
            sourceModeSource.isSelected -> RebaseSourceMode.SOURCE
            else -> RebaseSourceMode.BRANCH
        }

    private val selectedDestinationMode: RebaseDestinationMode
        get() = when {
            destModeOnto.isSelected -> RebaseDestinationMode.ONTO
            destModeAfter.isSelected -> RebaseDestinationMode.INSERT_AFTER
            else -> RebaseDestinationMode.INSERT_BEFORE
        }

    override fun doValidate(): ValidationInfo? {
        if (selectedDestinationIds().isEmpty()) {
            return ValidationInfo(JujutsuBundle.message("dialog.rebase.destination.none"), destinationTable)
        }
        return null
    }

    override fun doOKAction() {
        val destinations = selectedDestinationEntries().map { it.id as Revision }

        result = RebaseSpec(
            revisions = sourceEntries.map { it.id },
            destinations = destinations,
            sourceMode = selectedSourceMode,
            destinationMode = selectedDestinationMode
        )
        super.doOKAction()
    }
}
