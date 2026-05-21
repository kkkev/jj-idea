package `in`.kkkev.jjidea.ui.squash

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
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import `in`.kkkev.jjidea.ui.components.*
import `in`.kkkev.jjidea.ui.log.*
import `in`.kkkev.jjidea.ui.rebase.RebaseSimulator
import `in`.kkkev.jjidea.vcs.filePath
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*
import javax.swing.event.DocumentEvent

/**
 * Result of the squash dialog — the user's chosen parameters.
 *
 * Used by both "Squash into Parent" and "Squash Into" flows.
 */
data class SquashIntoSpec(
    val sources: List<Revision>,
    val destination: Revision,
    val filePaths: List<FilePath>,
    val description: Description,
    val keepEmptied: Boolean
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
 * Operates in two modes:
 * - **Free mode** (`candidateDestinations == null`): full searchable destination picker over all mutable changes.
 * - **Parent mode** (`candidateDestinations != null`): destination restricted to the given list (typically the source's parents).
 *   Search field is hidden; the table is pre-populated and pre-selected.
 *
 * Used by both "Squash into Parent" (parent mode, single source) and "Squash Into" (free mode, one or more sources).
 */
class SquashIntoDialog(
    private val project: Project,
    private val repo: JujutsuRepository,
    private val sourceEntries: List<LogEntry>,
    changes: List<Change>,
    allEntries: List<LogEntry> = emptyList(),
    preSelectedFiles: Set<FilePath> = emptySet(),
    private val candidateDestinations: List<LogEntry>? = null
) : DialogWrapper(project) {
    var result: SquashIntoSpec? = null
        private set

    private val parentMode = candidateDestinations != null
    private val repoEntries = allEntries.filter { it.repo == repo }
    private val sourceIds = sourceEntries.map { it.id }.toSet()

    // Destination picker
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = JujutsuBundle.message("dialog.squash.into.destination.search")
    }
    private val destTableModel = JujutsuLogTableModel()
    private var destGraphNodes: Map<ChangeId, GraphNode> = emptyMap()
    private val destinationTable = JBTable(destTableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        tableHeader.isVisible = false
        rowHeight = JBUI.scale(22)
        setStriped(true)
    }

    internal val fileSelection = FileSelectionPanel(project)
    internal val descriptionText: String get() = descriptionField.text

    private var userEditedDescription = false
    private val descriptionField = JBTextArea(4, 0)
    private val keepEmptiedCheckBox = JBCheckBox(JujutsuBundle.message("dialog.squash.into.keep.emptied"))

    init {
        title = JujutsuBundle.message(
            if (parentMode) "dialog.squash.into.parent.title" else "dialog.squash.into.title"
        )
        setOKButtonText(JujutsuBundle.message("dialog.squash.into.button"))

        if (preSelectedFiles.isNotEmpty()) {
            val included = changes.filter { it.filePath in preSelectedFiles }
            fileSelection.setChanges(changes, included)
        } else {
            fileSelection.setChanges(changes)
        }

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = loadDestinations(searchField.text)
        })

        destinationTable.selectionModel.addListSelectionListener { updateDescription() }

        descriptionField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                userEditedDescription = true
            }
        })

        init()

        if (parentMode) {
            setupCandidateMode()
        } else {
            loadDestinations("")
        }
        hideExtraColumns()
        updateDestRenderer()
    }

    override fun createCenterPanel(): JComponent {
        // Fixed-height top: source pane + separator + destination label + optional search field
        val topSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(createSectionLabel(JujutsuBundle.message("dialog.squash.into.source")))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(createSourcePane())
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(JSeparator().apply { alignmentX = JPanel.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createSectionLabel(JujutsuBundle.message("dialog.squash.into.destination")))
            if (!parentMode) {
                add(Box.createVerticalStrut(JBUI.scale(4)))
                add(searchField.apply { alignmentX = JPanel.LEFT_ALIGNMENT })
                add(Box.createVerticalStrut(JBUI.scale(4)))
            }
        }

        // Fixed-height bottom: description + keep emptied
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
            add(keepEmptiedCheckBox.apply { alignmentX = JPanel.LEFT_ALIGNMENT })
        }

        val destScrollPane = JBScrollPane(destinationTable).apply {
            border = JBUI.Borders.empty()
            // In parent mode, fix the height to exactly the number of candidate rows so the
            // file-selection panel gets all remaining space. In free mode, let the splitter decide.
            if (parentMode) {
                val fixedHeight = candidateDestinations!!.size * destinationTable.rowHeight + JBUI.scale(4)
                preferredSize = Dimension(0, fixedHeight)
                maximumSize = Dimension(Int.MAX_VALUE, fixedHeight)
            }
        }
        val filePanel = JPanel(BorderLayout()).apply {
            add(
                createSectionLabel(JujutsuBundle.message("dialog.squash.into.files")),
                BorderLayout.NORTH
            )
            add(fileSelection, BorderLayout.CENTER)
        }
        // In parent mode: fixed-height destination box above, file panel takes the rest.
        // In free mode: splitter lets the user resize the two halves.
        val centerContent: JComponent = if (parentMode) {
            JPanel(BorderLayout()).apply {
                add(destScrollPane, BorderLayout.NORTH)
                add(filePanel, BorderLayout.CENTER)
            }
        } else {
            OnePixelSplitter(true, 0.6f).apply {
                firstComponent = destScrollPane
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

    private fun createSourcePane() = IconAwareHtmlPane(project).apply {
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

    private fun setupCandidateMode() {
        val candidates = candidateDestinations!!
        destTableModel.setEntries(candidates)
        destGraphNodes = CommitGraphBuilder().buildGraph(candidates)
        if (candidates.isNotEmpty()) {
            destinationTable.setRowSelectionInterval(0, 0)
            // selection listener fires → updateDescription()
        } else {
            updateDescription()
        }
    }

    private fun loadDestinations(query: String) {
        val trimmed = query.trim()
        val excluded = RebaseSimulator.excludedDestinationIds(
            repoEntries,
            sourceIds,
            RebaseSourceMode.SOURCE
        )
        val matchesSearch = { entry: LogEntry ->
            trimmed.isEmpty() ||
                entry.id.short.contains(trimmed, ignoreCase = true) ||
                entry.id.full.contains(trimmed, ignoreCase = true) ||
                entry.description.display.contains(trimmed, ignoreCase = true) ||
                entry.bookmarks.any { it.name.contains(trimmed, ignoreCase = true) }
        }
        val previousSelection = selectedDestinationId()
        val filtered = repoEntries.filter {
            it.id !in excluded && !it.immutable && matchesSearch(it)
        }

        destTableModel.setEntries(filtered)
        destGraphNodes = CommitGraphBuilder().buildGraph(filtered)
        updateDestRenderer()

        // Restore previous selection if still available
        if (previousSelection != null) {
            for (i in 0 until destTableModel.rowCount) {
                if (destTableModel.getEntry(i)?.id == previousSelection) {
                    destinationTable.setRowSelectionInterval(i, i)
                    break
                }
            }
        }
    }

    private fun hideExtraColumns() {
        val toRemove = (destinationTable.columnCount - 1 downTo 0)
            .filter { it != JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION }
        for (col in toRemove) {
            if (col < destinationTable.columnModel.columnCount) {
                destinationTable.removeColumn(destinationTable.columnModel.getColumn(col))
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

    private fun updateDescription() {
        if (userEditedDescription) return
        val destEntry = selectedDestinationEntry()
        // In free mode, skip update until user selects a destination
        if (destEntry == null && !parentMode) return
        val merged = mergeDescriptions(
            destEntry?.description?.actual ?: "",
            sourceEntries.map { it.description.actual }
        )
        // Temporarily stop listening to avoid setting the dirty flag
        userEditedDescription = true
        descriptionField.text = merged
        userEditedDescription = false
    }

    private fun selectedDestinationId(): ChangeId? =
        destinationTable.selectedRow.takeIf { it >= 0 }?.let { destTableModel.getEntry(it)?.id }

    private fun selectedDestinationEntry(): LogEntry? =
        destinationTable.selectedRow.takeIf { it >= 0 }?.let { destTableModel.getEntry(it) }

    override fun doValidate(): ValidationInfo? {
        if (selectedDestinationId() == null) {
            return ValidationInfo(
                JujutsuBundle.message("dialog.squash.into.destination.none"),
                destinationTable
            )
        }
        if (fileSelection.includedChanges.isEmpty()) {
            return ValidationInfo(JujutsuBundle.message("dialog.squash.no.files"), fileSelection)
        }
        return null
    }

    override fun doOKAction() {
        val destEntry = selectedDestinationEntry() ?: return
        val filePaths =
            if (fileSelection.allIncluded) {
                emptyList()
            } else {
                fileSelection.includedChanges.mapNotNull { it.filePath }
            }
        result = SquashIntoSpec(
            sources = sourceEntries.map { it.id },
            destination = destEntry.id,
            filePaths = filePaths,
            description = Description(descriptionField.text.trim()),
            keepEmptied = keepEmptiedCheckBox.isSelected
        )
        super.doOKAction()
    }
}
