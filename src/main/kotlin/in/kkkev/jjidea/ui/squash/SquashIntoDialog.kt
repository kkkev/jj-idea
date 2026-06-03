package `in`.kkkev.jjidea.ui.squash

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import `in`.kkkev.jjidea.ui.components.*
import `in`.kkkev.jjidea.ui.log.*
import `in`.kkkev.jjidea.ui.rebase.RebaseSimulator
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.filePath
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*
import javax.swing.event.DocumentEvent

/**
 * Determines which side of the squash dialog is fixed and which is picked by the user.
 *
 * - [PickDestination]: sources are fixed, user picks a destination.
 *   [candidates] restricts the picker to specific entries (parent mode); null means all mutable.
 * - [PickSources]: destination is fixed, user multi-selects one or more sources.
 *   Used by "Squash Into Here" (right-click on destination commit).
 */
sealed interface SquashMode {
    val candidates: List<LogEntry>?

    data class PickDestination(
        val sources: List<LogEntry>,
        override val candidates: List<LogEntry>? = null
    ) : SquashMode

    data class PickSources(
        val destination: LogEntry,
        override val candidates: List<LogEntry>? = null
    ) : SquashMode
}

/**
 * Result of the squash dialog — the user's chosen parameters.
 *
 * Used by all squash flows.
 */
data class SquashIntoSpec(
    val sources: List<Revision>,
    val destination: Revision,
    val filePaths: List<FilePath>,
    val description: Description?,
    val deleteEmptyAndMoveWorkingCopy: Boolean
)

/**
 * Merge descriptions for a combined change.
 * Non-empty descriptions are joined with blank lines; empty ones are skipped.
 */
fun mergeDescriptions(parent: String, sources: List<String>): String =
    (listOf(parent) + sources).filter { it.isNotEmpty() }.joinToString("\n\n")

fun mergeDescriptions(parent: String, source: String): String =
    mergeDescriptions(parent, listOf(source))

/**
 * Dialog for configuring a `jj squash --from ... --into ...` operation.
 *
 * Operates in two fundamental modes (see [SquashMode]):
 * - [SquashMode.PickDestination]: sources are fixed, user picks a destination.
 *   Sub-modes: free (searchable) or predefined-candidates (parent mode).
 * - [SquashMode.PickSources]: destination is fixed, user multi-selects sources.
 */
class SquashIntoDialog(
    private val project: Project,
    private val repo: JujutsuRepository,
    private val mode: SquashMode,
    changes: List<Change>,
    preSelectedFiles: Set<FilePath> = emptySet()
) : DialogWrapper(project) {
    var result: SquashIntoSpec? = null
        private set

    private val pickingSources = mode is SquashMode.PickSources
    private val hasPredefinedCandidates = mode.candidates != null
    private var repoEntries: List<LogEntry> = emptyList()
    private val sourceIds = (mode as? SquashMode.PickDestination)?.sources?.map { it.id }?.toSet() ?: emptySet()

    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = JujutsuBundle.message(
            if (pickingSources) {
                "dialog.squash.into.source.search"
            } else {
                "dialog.squash.into.destination.search"
            }
        )
    }
    private val pickerTableModel = JujutsuLogTableModel()
    private var pickerGraphNodes: Map<ChangeId, GraphNode> = emptyMap()
    private val pickerTable = JBTable(pickerTableModel).apply {
        setSelectionMode(
            if (pickingSources) {
                ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            } else {
                ListSelectionModel.SINGLE_SELECTION
            }
        )
        tableHeader.isVisible = false
        rowHeight = JBUI.scale(22)
        setStriped(true)
    }

    internal val fileSelection = FileSelectionPanel(project)
    internal val descriptionText: String get() = descriptionField.text
    internal var deleteEmptyAndMoveIsSelected: Boolean
        get() = deleteEmptyAndMoveCheckBox.isSelected
        set(value) {
            deleteEmptyAndMoveCheckBox.isSelected = value
        }

    @org.jetbrains.annotations.TestOnly
    internal fun performOKForTest() = doOKAction()

    @org.jetbrains.annotations.TestOnly
    internal fun selectPickerRowsForTest(vararg rows: Int) {
        pickerTable.clearSelection()
        rows.forEach { pickerTable.addRowSelectionInterval(it, it) }
    }

    private var userEditedDescription = false
    private var loadGeneration = 0
    private val descriptionField = JBTextArea(4, 0)
    private val deleteEmptyAndMoveCheckBox = JBCheckBox(
        JujutsuBundle.message("dialog.squash.into.delete.empty.and.move")
    ).apply {
        isSelected = JujutsuSettings.getInstance(project).state.squashDeleteEmptyAndMove
    }

    init {
        title = when {
            pickingSources -> JujutsuBundle.message("dialog.squash.from.title")
            hasPredefinedCandidates -> JujutsuBundle.message("dialog.squash.into.parent.title")
            else -> JujutsuBundle.message("dialog.squash.into.title")
        }
        setOKButtonText(JujutsuBundle.message("dialog.squash.into.button"))

        if (mode is SquashMode.PickDestination) {
            if (preSelectedFiles.isNotEmpty()) {
                fileSelection.setChanges(changes, changes.filter { it.filePath in preSelectedFiles })
            } else {
                fileSelection.setChanges(changes)
            }
        }

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = loadCandidates(searchField.text)
        })

        pickerTable.selectionModel.addListSelectionListener {
            updateDescription()
            if (pickingSources) reloadChangesForSelection()
        }

        descriptionField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                userEditedDescription = true
            }
        })

        fileSelection.addInclusionListener {
            updateDeleteEmptyEnabled()
            updateDescription()
            initValidation()
        }
        fileSelection.changesTree.invokeAfterRefresh { updateDeleteEmptyEnabled() }

        init()

        if (hasPredefinedCandidates) {
            setupPredefinedCandidates()
            if (pickingSources) preSelectWorkingCopy()
        } else {
            runInBackground(ModalityState.any()) {
                val entries = loadRepoEntries(project, repo)
                runLater {
                    if (!isDisposed) {
                        repoEntries = entries
                        loadCandidates("")
                        if (pickingSources) preSelectWorkingCopy()
                    }
                }
            }
        }
        hideExtraColumns()
        updatePickerRenderer()
        updateDeleteEmptyEnabled()
    }

    private fun updateDeleteEmptyEnabled() {
        deleteEmptyAndMoveCheckBox.isEnabled = fileSelection.allIncluded
    }

    override fun createCenterPanel(): JComponent {
        val fixedLabel = JujutsuBundle.message(
            if (pickingSources) "dialog.squash.into.destination" else "dialog.squash.into.source"
        )
        val pickerLabel = JujutsuBundle.message(
            if (pickingSources) "dialog.squash.into.source" else "dialog.squash.into.destination"
        )

        val topSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(createSectionLabel(fixedLabel))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(createFixedSidePane())
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(JSeparator().apply { alignmentX = JPanel.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createSectionLabel(pickerLabel))
            if (!hasPredefinedCandidates) {
                add(Box.createVerticalStrut(JBUI.scale(4)))
                add(searchField.apply { alignmentX = JPanel.LEFT_ALIGNMENT })
                add(Box.createVerticalStrut(JBUI.scale(4)))
            }
        }

        val bottomSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createSectionLabel(JujutsuBundle.message("dialog.squash.into.description")))
            val scrollPane = JScrollPane(descriptionField).apply {
                alignmentX = JPanel.LEFT_ALIGNMENT
                preferredSize = Dimension(0, JBUI.scale(80))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(80))
            }
            add(scrollPane)
            add(deleteEmptyAndMoveCheckBox.apply { alignmentX = JPanel.LEFT_ALIGNMENT })
        }

        val pickerScrollPane = JBScrollPane(pickerTable).apply {
            border = JBUI.Borders.empty()
            if (hasPredefinedCandidates) {
                val fixedHeight = mode.candidates!!.size * pickerTable.rowHeight + JBUI.scale(4)
                preferredSize = Dimension(0, fixedHeight)
                maximumSize = Dimension(Int.MAX_VALUE, fixedHeight)
            }
        }
        val filePanel = JPanel(BorderLayout()).apply {
            add(createSectionLabel(JujutsuBundle.message("dialog.squash.into.files")), BorderLayout.NORTH)
            add(fileSelection, BorderLayout.CENTER)
        }
        val centerContent: JComponent = if (hasPredefinedCandidates) {
            JPanel(BorderLayout()).apply {
                add(pickerScrollPane, BorderLayout.NORTH)
                add(filePanel, BorderLayout.CENTER)
            }
        } else {
            OnePixelSplitter(true, 0.6f).apply {
                firstComponent = pickerScrollPane
                secondComponent = filePanel
            }
        }

        val wrapper = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(topSection, BorderLayout.NORTH)
            add(centerContent, BorderLayout.CENTER)
            add(bottomSection, BorderLayout.SOUTH)
        }
        wrapper.preferredSize = Dimension(JBUI.scale(800), JBUI.scale(650))
        return wrapper
    }

    private fun createSectionLabel(text: String) = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
        alignmentX = JLabel.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(4, 0)
    }

    private fun createFixedSidePane() = IconAwareHtmlPane(project).apply {
        alignmentX = JPanel.LEFT_ALIGNMENT
        val entries = when (mode) {
            is SquashMode.PickDestination -> mode.sources
            is SquashMode.PickSources -> listOf(mode.destination)
        }
        text = htmlString {
            append(entries, separator = "\n") { entry ->
                appendStatusIndicators(entry)
                append(entry.id)
                append(" ")
                appendDescriptionAndEmptyIndicator(entry)
                append(" ")
                appendDecorations(entry)
            }
        }
    }

    private fun setupPredefinedCandidates() {
        val candidates = mode.candidates!!
        pickerTableModel.setEntries(candidates)
        pickerGraphNodes = CommitGraphBuilder().buildGraph(candidates)
        if (candidates.isNotEmpty()) {
            if (!pickingSources) pickerTable.setRowSelectionInterval(0, 0)
        } else {
            updateDescription()
        }
    }

    private fun loadCandidates(query: String) {
        val trimmed = query.trim()
        val matchesSearch = { entry: LogEntry ->
            trimmed.isEmpty() ||
                entry.id.short.contains(trimmed, ignoreCase = true) ||
                entry.id.full.contains(trimmed, ignoreCase = true) ||
                entry.description.display.contains(trimmed, ignoreCase = true) ||
                entry.bookmarks.any { it.name.name.contains(trimmed, ignoreCase = true) }
        }
        val filtered = when (mode) {
            is SquashMode.PickDestination -> {
                val excluded = RebaseSimulator.excludedDestinationIds(repoEntries, sourceIds, RebaseSourceMode.REVISION)
                repoEntries.filter { it.id !in excluded && !it.immutable && matchesSearch(it) }
            }
            is SquashMode.PickSources -> {
                val destId = mode.destination.id
                repoEntries.filter { it.id != destId && !it.immutable && matchesSearch(it) }
            }
        }

        val previousSelection = if (pickingSources) {
            selectedSourceIds()
        } else {
            selectedDestinationId()?.let { setOf(it) }
                ?: emptySet()
        }

        pickerTableModel.setEntries(filtered)
        pickerGraphNodes = CommitGraphBuilder().buildGraph(filtered)
        updatePickerRenderer()

        if (previousSelection.isNotEmpty()) {
            if (pickingSources) pickerTable.clearSelection()
            for (i in 0 until pickerTableModel.rowCount) {
                val id = pickerTableModel.getEntry(i)?.id ?: continue
                if (id !in previousSelection) continue
                if (pickingSources) {
                    pickerTable.addRowSelectionInterval(i, i)
                } else {
                    pickerTable.setRowSelectionInterval(i, i)
                    break
                }
            }
        }
    }

    private fun preSelectWorkingCopy() {
        for (i in 0 until pickerTableModel.rowCount) {
            if (pickerTableModel.getEntry(i)?.isWorkingCopy == true) {
                pickerTable.addRowSelectionInterval(i, i)
                break
            }
        }
    }

    private fun reloadChangesForSelection() {
        val sources = selectedSourceEntries()
        val gen = ++loadGeneration
        if (sources.isEmpty()) {
            fileSelection.setChanges(emptyList())
            return
        }
        runInBackground {
            val loaded = ChangeService.loadChanges(sources)
            runLater { if (loadGeneration == gen) fileSelection.setChanges(loaded) }
        }
    }

    private fun hideExtraColumns() {
        val toRemove = (pickerTable.columnCount - 1 downTo 0)
            .filter { it != JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION }
        for (col in toRemove) {
            if (col < pickerTable.columnModel.columnCount) {
                pickerTable.removeColumn(pickerTable.columnModel.getColumn(col))
            }
        }
    }

    private fun updatePickerRenderer() {
        for (i in 0 until pickerTable.columnModel.columnCount) {
            val column = pickerTable.columnModel.getColumn(i)
            if (column.modelIndex == JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION) {
                column.cellRenderer = JujutsuGraphAndDescriptionRenderer(pickerGraphNodes)
                break
            }
        }
    }

    private fun updateDescription() {
        if (userEditedDescription) return
        val destDesc: String
        val sourceDescs: List<String>
        when (mode) {
            is SquashMode.PickDestination -> {
                val destEntry = selectedDestinationEntry()
                if (destEntry == null && !hasPredefinedCandidates) return
                destDesc = destEntry?.description?.actual ?: ""
                sourceDescs = mode.sources.map { it.description.actual }
            }
            is SquashMode.PickSources -> {
                val sources = selectedSourceEntries()
                if (sources.isEmpty()) return
                destDesc = mode.destination.description.actual
                sourceDescs = sources.map { it.description.actual }
            }
        }
        val text = if (fileSelection.allIncluded) mergeDescriptions(destDesc, sourceDescs) else destDesc
        userEditedDescription = true
        descriptionField.text = text
        userEditedDescription = false
    }

    private fun selectedDestinationId(): ChangeId? =
        pickerTable.selectedRow.takeIf { it >= 0 }?.let { pickerTableModel.getEntry(it)?.id }

    private fun selectedDestinationEntry(): LogEntry? =
        pickerTable.selectedRow.takeIf { it >= 0 }?.let { pickerTableModel.getEntry(it) }

    private fun selectedSourceIds(): Set<ChangeId> =
        pickerTable.selectedRows.toList().mapNotNull { pickerTableModel.getEntry(it)?.id }.toSet()

    private fun selectedSourceEntries(): List<LogEntry> =
        pickerTable.selectedRows.toList().mapNotNull { pickerTableModel.getEntry(it) }

    override fun doValidate(): ValidationInfo? {
        when {
            pickingSources -> if (selectedSourceEntries().isEmpty()) {
                return ValidationInfo(JujutsuBundle.message("dialog.squash.into.source.none"), pickerTable)
            }
            else -> if (selectedDestinationId() == null) {
                return ValidationInfo(JujutsuBundle.message("dialog.squash.into.destination.none"), pickerTable)
            }
        }
        if (fileSelection.includedChanges.isEmpty()) {
            return ValidationInfo(JujutsuBundle.message("dialog.squash.into.no.files"), fileSelection)
        }
        return null
    }

    override fun doOKAction() {
        val filePaths = if (fileSelection.allIncluded) {
            emptyList()
        } else {
            fileSelection.includedChanges.map { it.filePath }
        }
        val deleteAndMove = deleteEmptyAndMoveCheckBox.isEnabled && deleteEmptyAndMoveCheckBox.isSelected
        if (deleteEmptyAndMoveCheckBox.isEnabled) {
            JujutsuSettings.getInstance(project).state.squashDeleteEmptyAndMove = deleteAndMove
        }
        when (mode) {
            is SquashMode.PickDestination -> {
                val destEntry = selectedDestinationEntry() ?: return
                val destDesc = destEntry.description.actual
                val sourceDescs = mode.sources.map { it.description.actual }
                val combining =
                    fileSelection.allIncluded && destDesc.isNotEmpty() && sourceDescs.any { it.isNotEmpty() }
                val description = if (userEditedDescription ||
                    combining
                ) {
                    Description(descriptionField.text.trim())
                } else {
                    null
                }
                result = SquashIntoSpec(
                    sources = mode.sources.map { it.id },
                    destination = destEntry.id,
                    filePaths = filePaths,
                    description = description,
                    deleteEmptyAndMoveWorkingCopy = deleteAndMove
                )
            }
            is SquashMode.PickSources -> {
                val sourceEntries = selectedSourceEntries()
                if (sourceEntries.isEmpty()) return
                val destDesc = mode.destination.description.actual
                val sourceDescs = sourceEntries.map { it.description.actual }
                val combining =
                    fileSelection.allIncluded && destDesc.isNotEmpty() && sourceDescs.any { it.isNotEmpty() }
                val description = if (userEditedDescription ||
                    combining
                ) {
                    Description(descriptionField.text.trim())
                } else {
                    null
                }
                result = SquashIntoSpec(
                    sources = sourceEntries.map { it.id },
                    destination = mode.destination.id,
                    filePaths = filePaths,
                    description = description,
                    deleteEmptyAndMoveWorkingCopy = deleteAndMove
                )
            }
        }
        super.doOKAction()
    }
}
