package `in`.kkkev.jjidea.ui.squash

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.diffedit.HunkPicker
import `in`.kkkev.jjidea.diffedit.HunkPickerLabels
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.common.*
import `in`.kkkev.jjidea.ui.components.DescriptionEditor
import `in`.kkkev.jjidea.ui.log.CommitGraphBuilder
import `in`.kkkev.jjidea.ui.log.GraphNode
import `in`.kkkev.jjidea.ui.log.JujutsuGraphAndDescriptionRenderer
import `in`.kkkev.jjidea.ui.log.JujutsuLogTableModel
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
 * Used by all squash flows. [hunkSelection] is non-null only for a single-source squash with at
 * least one file partially picked (see [SquashIntoDialog]'s hunk-picking section) — jj's diff
 * editor is one before/after pair, so hunk-level squashing across multiple sources isn't
 * well-defined and "Pick Hunks…" is unavailable whenever more than one source is selected.
 */
data class SquashIntoSpec(
    val sources: List<Revision>,
    val destination: Revision,
    val filePaths: List<FilePath>,
    val description: Description?,
    val deleteEmptyAndMoveWorkingCopy: Boolean,
    val hunkSelection: HunkSelection? = null
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
    private var pickerGraphNodes: Map<ChangeKey, GraphNode> = emptyMap()
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
        setStriped(JujutsuSettings.getInstance(project).state.stripedLogRows)
    }

    internal val fileSelection = FileSelectionPanel(project)
    private var previousIncluded: Set<FilePath> = emptySet()

    // --- Destination overrides: hunk-picked content for partially-squashed files ---
    // Non-null entry = this file has partial content picked via the hunk picker.
    private val destinationOverrides: MutableMap<FilePath, String> = LinkedHashMap()

    // --- Right panel: native diff preview, showing what moves into the destination ---
    // cache + lazy-load shared with SplitDialog.
    private val previewController = HunkPickPreviewController(
        project = project,
        disposable = disposable,
        loadContents = ::loadSquashFileData,
        resolveContent = { fp, included, contents ->
            destinationOverrides[fp] ?: computePreviewAfterContent(included, null, contents.before, contents.after)
        },
        previewTitles = { content, contents -> describeSquashState(content, contents.before, contents.after) },
        isIncluded = { fp -> fileSelection.includedChanges.any { it.filePath == fp } }
    )
    internal val preview get() = previewController.preview

    // --- "Pick Hunks…" button ---
    // Only offered for a single-source squash - jj's diff editor is one before/after pair, so
    // hunk-level squashing across multiple sources isn't well-defined (see SquashIntoSpec's KDoc).
    internal val pickHunksButton = previewController.pickHunksButton.apply {
        addActionListener { onPickHunks() }
    }

    /** True when exactly one source is currently selected — see [pickHunksButton]'s KDoc. */
    private val singleSource: Boolean
        get() = when (mode) {
            is SquashMode.PickDestination -> mode.sources.size == 1
            is SquashMode.PickSources -> selectedSourceEntries().size == 1
        }

    private fun updatePickHunksVisibility() {
        pickHunksButton.isVisible = singleSource
    }

    // --- Test seam: injectable hunk picker (avoids modal picker under tests) ---
    @org.jetbrains.annotations.TestOnly
    internal var hunkPickerForTest: ((FilePath) -> String?)? = null

    internal val descriptionText: String get() = descriptionEditor.text.actual
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

    /** Set a hunk-picker override for a file directly (for testing without the modal picker). */
    @org.jetbrains.annotations.TestOnly
    internal fun setDestinationOverrideForTest(filePath: FilePath, content: String?) {
        if (content != null) {
            destinationOverrides[filePath] = content
        } else {
            destinationOverrides.remove(filePath)
        }
        syncPartialChanges()
        updateDeleteEmptyEnabled()
    }

    private var userEditedDescription = false
    private var loadGeneration = 0
    private val descriptionEditor = DescriptionEditor(project).apply {
        Disposer.register(disposable, this)
    }
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
            previousIncluded = fileSelection.includedChanges.map { it.filePath }.toSet()
        }

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = loadCandidates(searchField.text)
        })

        pickerTable.selectionModel.addListSelectionListener {
            updateDescription()
            updatePickHunksVisibility()
            if (pickingSources) reloadChangesForSelection()
        }

        descriptionEditor.addTextChangeListener { userEditedDescription = true }

        fileSelection.addInclusionListener {
            onFileInclusionChanged()
            updateDeleteEmptyEnabled()
            updateDescription()
            initValidation()
        }
        fileSelection.changesTree.invokeAfterRefresh { updateDeleteEmptyEnabled() }

        fileSelection.changesTree.addTreeSelectionListener {
            val selected = fileSelection.changesTree.selectedChanges.firstOrNull()
            if (selected != null) previewController.showFor(selected)
        }

        init()

        if (hasPredefinedCandidates) {
            setupPredefinedCandidates()
            if (pickingSources) preSelectWorkingCopy()
        } else {
            runInBackground(ModalityState.any()) {
                val entries = repo.logCache.all
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
        updatePickHunksVisibility()
    }

    private fun updateDeleteEmptyEnabled() {
        // A partial squash (any file has a hunk-picked override) can never empty the source, so
        // the checkbox is meaningless once hunk-picking is in play - see doOKAction's KDoc.
        deleteEmptyAndMoveCheckBox.isEnabled = fileSelection.allIncluded && destinationOverrides.isEmpty()
    }

    // ---- File inclusion sync ----

    private fun onFileInclusionChanged() {
        val nowIncluded = fileSelection.includedChanges.map { it.filePath }.toSet()
        for (fp in (previousIncluded - nowIncluded) + (nowIncluded - previousIncluded)) {
            destinationOverrides.remove(fp)
        }
        previousIncluded = nowIncluded

        previewController.currentFile?.let { fp -> previewController.refresh(fp) }
        syncPartialChanges()
    }

    private fun syncPartialChanges() {
        fileSelection.setPartialChanges(
            fileSelection.changesTree.changes.filter { it.filePath in destinationOverrides }.toSet()
        )
    }

    // ---- File diff loading + preview ----
    // Mirrors in.kkkev.jjidea.ui.split.SplitDialog's preview, but the diff shown is different:
    // left = this file's content before the source touched it (fixed), right = that content
    // plus this file's change if ticked (or the hunk-picked override, if any) - i.e. the
    // destination's result. See SquashFilePreview.kt for why this framing (anchored to the
    // source's own before/after) is the one well-defined in every squash mode.

    // ---- Hunk picker ----

    private fun onPickHunks() {
        val fp = previewController.currentFile ?: return
        val contents = previewController.cachedContents(fp) ?: return
        val included = fileSelection.includedChanges.any { it.filePath == fp }

        // Resume any existing partial pick; otherwise start from the tick-derived default.
        val initialContent = destinationOverrides[fp]
            ?: computePreviewAfterContent(included, null, contents.before, contents.after)

        val destinationEntry = when (mode) {
            is SquashMode.PickDestination -> selectedDestinationEntry()
            is SquashMode.PickSources -> mode.destination
        }
        val sourceEntry = when (mode) {
            is SquashMode.PickDestination -> mode.sources.singleOrNull()
            is SquashMode.PickSources -> selectedSourceEntries().singleOrNull()
        }
        val sourceLabel = sourceEntry?.id?.short ?: JujutsuBundle.message("dialog.squash.into.source")
        val destinationLabel = destinationEntry?.id?.short ?: JujutsuBundle.message("dialog.squash.into.destination")

        val pickedContent: String? = hunkPickerForTest?.invoke(fp)
            ?: HunkPicker.pickRemainderContent(
                project = project,
                fileName = fp.name,
                fileType = contents.fileType,
                baseContent = contents.before,
                afterContent = contents.after,
                initialContent = initialContent,
                labels = HunkPickerLabels.forSquash(sourceLabel, destinationLabel)
            )

        if (pickedContent == null) return // user cancelled — keep prior state

        applyPickedContent(fp, pickedContent, contents.before, contents.after)
        previewController.refresh(fp)
        updateDeleteEmptyEnabled()
    }

    /**
     * Apply a hunk-picker result for [fp]. Fully-none/fully-all results are genuinely resolved
     * states and adjust the tick accordingly; anything else is a genuine partial, which stores
     * the destination override but **deliberately leaves the tick state untouched** — mirrors
     * [in.kkkev.jjidea.ui.split.SplitDialog.applyPickedContent]'s reasoning exactly, just with
     * the opposite polarity (ticked here means "fully squashed", not "fully moved away").
     */
    internal fun applyPickedContent(fp: FilePath, pickedContent: String, before: String, after: String) {
        val change = fileSelection.changesTree.changes.find { it.filePath == fp } ?: return
        when (pickedContent) {
            after -> {
                // Destination gets everything → file fully squashed, tick it.
                destinationOverrides.remove(fp)
                fileSelection.setIncluded(change, true)
                previousIncluded = fileSelection.includedChanges.map { it.filePath }.toSet()
            }

            before -> {
                // Destination gets nothing → nothing squashed, untick it.
                destinationOverrides.remove(fp)
                fileSelection.setIncluded(change, false)
                previousIncluded = fileSelection.includedChanges.map { it.filePath }.toSet()
            }

            else -> {
                destinationOverrides[fp] = pickedContent
            }
        }
        syncPartialChanges()
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
            // CommitMessage scrolls itself - no JScrollPane wrapper needed, unlike the old JBTextArea.
            descriptionEditor.component.apply {
                alignmentX = JPanel.LEFT_ALIGNMENT
                preferredSize = Dimension(0, JBUI.scale(80))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(80))
            }
            add(descriptionEditor.component)
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
        val pickerAndFiles: JComponent = if (hasPredefinedCandidates) {
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

        preview.addFooterComponent(pickHunksButton)

        val centerContent = OnePixelSplitter(false, 0.45f).apply {
            firstComponent = pickerAndFiles
            secondComponent = preview
        }

        val wrapper = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(topSection, BorderLayout.NORTH)
            add(centerContent, BorderLayout.CENTER)
            add(bottomSection, BorderLayout.SOUTH)
        }
        wrapper.preferredSize = Dimension(JBUI.scale(1150), JBUI.scale(650))
        return wrapper
    }

    private fun createSectionLabel(text: String) = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
        alignmentX = JLabel.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(4, 0)
    }

    private fun createFixedSidePane() = createSourcePanel(
        project,
        when (mode) {
            is SquashMode.PickDestination -> mode.sources
            is SquashMode.PickSources -> listOf(mode.destination)
        }
    )

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
        resetPreview()
        if (sources.isEmpty()) {
            fileSelection.setChanges(emptyList())
            return
        }
        runInBackground {
            val loaded = ChangeService.loadChanges(sources)
            runLater { if (loadGeneration == gen) fileSelection.setChanges(loaded) }
        }
    }

    /**
     * Clear the preview, its cache, and any hunk-picked overrides — the file tree is about to be
     * replaced wholesale (a new source selection means the previous overrides no longer apply to
     * any real file).
     */
    private fun resetPreview() {
        previewController.reset()
        destinationOverrides.clear()
        previousIncluded = emptySet()
        updateDeleteEmptyEnabled()
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
        val text = if (fileSelection.allIncluded && !isPartialSquash) {
            mergeDescriptions(destDesc, sourceDescs)
        } else {
            destDesc
        }
        userEditedDescription = true
        descriptionEditor.text = Description(text)
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

    /** True when any file has a hunk-picked destination override — see [applyPickedContent]. */
    private val isPartialSquash: Boolean get() = destinationOverrides.isNotEmpty()

    override fun doValidate(): ValidationInfo? {
        when {
            pickingSources -> if (selectedSourceEntries().isEmpty()) {
                return ValidationInfo(JujutsuBundle.message("dialog.squash.into.source.none"), pickerTable)
            }

            else -> if (selectedDestinationId() == null) {
                return ValidationInfo(JujutsuBundle.message("dialog.squash.into.destination.none"), pickerTable)
            }
        }
        if (fileSelection.includedChanges.isEmpty() && !isPartialSquash) {
            return ValidationInfo(JujutsuBundle.message("dialog.squash.into.no.files"), fileSelection)
        }
        return null
    }

    /**
     * Build the hunk-level selection for the current changes, or null when [isPartialSquash] is
     * false (the fast whole-file path applies instead). Only reachable when [singleSource] is
     * true, since [pickHunksButton] is hidden otherwise and overrides can't exist.
     */
    private fun buildSquashHunkSelection(): HunkSelection? {
        if (!isPartialSquash) return null
        return buildHunkSelection(
            changes = fileSelection.changesTree.changes.toList(),
            root = repo.directory,
            overrides = destinationOverrides,
            isIncluded = { fp -> fileSelection.includedChanges.any { it.filePath == fp } },
            isDeletion = { it.afterRevision == null },
            contentFor = { change, included ->
                if (included) previewController.cachedContents(change.filePath)?.after else null
            }
        )
    }

    override fun doOKAction() {
        // A partial squash can't be described by a fileset (some files are only *partially*
        // squashed), and treating it as "all included" would wrongly merge the destination's and
        // sources' descriptions - see mergeDescriptions' callers below.
        val effectiveAllIncluded = fileSelection.allIncluded && !isPartialSquash
        val filePaths = if (effectiveAllIncluded) {
            emptyList()
        } else {
            fileSelection.includedChanges.map { it.filePath }
        }
        val hunkSelection = buildSquashHunkSelection()
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
                    effectiveAllIncluded && destDesc.isNotEmpty() && sourceDescs.any { it.isNotEmpty() }
                val description = if (userEditedDescription ||
                    combining
                ) {
                    Description(descriptionEditor.text.actual.trim())
                } else {
                    null
                }
                result = SquashIntoSpec(
                    sources = mode.sources.map { it.id },
                    destination = destEntry.id,
                    filePaths = filePaths,
                    description = description,
                    deleteEmptyAndMoveWorkingCopy = deleteAndMove,
                    hunkSelection = hunkSelection
                )
            }

            is SquashMode.PickSources -> {
                val sourceEntries = selectedSourceEntries()
                if (sourceEntries.isEmpty()) return
                val destDesc = mode.destination.description.actual
                val sourceDescs = sourceEntries.map { it.description.actual }
                val combining =
                    effectiveAllIncluded && destDesc.isNotEmpty() && sourceDescs.any { it.isNotEmpty() }
                val description = if (userEditedDescription ||
                    combining
                ) {
                    Description(descriptionEditor.text.actual.trim())
                } else {
                    null
                }
                result = SquashIntoSpec(
                    sources = sourceEntries.map { it.id },
                    destination = mode.destination.id,
                    filePaths = filePaths,
                    description = description,
                    deleteEmptyAndMoveWorkingCopy = deleteAndMove,
                    hunkSelection = hunkSelection
                )
            }
        }
        super.doOKAction()
    }
}
