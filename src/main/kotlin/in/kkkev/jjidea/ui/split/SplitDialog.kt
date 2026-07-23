package `in`.kkkev.jjidea.ui.split

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.diffedit.HunkDiffPicker
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import `in`.kkkev.jjidea.ui.components.*
import `in`.kkkev.jjidea.ui.log.appendDecorations
import `in`.kkkev.jjidea.ui.log.appendStatusIndicators
import `in`.kkkev.jjidea.util.GitDiffReverseApplier
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.relativeTo
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TreeSelectionListener

/**
 * Result of the split dialog.
 *
 * [filePaths] are the files that **remain in the parent/first** commit when [hunkSelection]
 * is null (whole-file fast path) — i.e. everything *not* ticked to move to the child. When
 * [hunkSelection] is non-null, at least one file has partial hunk selection and the split
 * must go through the diff-editor path.
 *
 * [childDescription] is applied via `jj describe` after split (null = keep original).
 */
data class SplitSpec(
    val revision: Revision,
    /** Whole-file fast path: files that stay in the parent (i.e. not ticked to move to the child). */
    val filePaths: List<FilePath>,
    /** Hunk-level selection. Non-null when at least one file is partially selected. */
    val hunkSelection: SplitHunkSelection?,
    val description: Description,
    val childDescription: Description?,
    val parallel: Boolean
)

/**
 * Dialog for configuring a `jj split` operation.
 *
 * Layout: left panel = changed-files list with file-level checkboxes + summary; right panel =
 * native read-only diff preview for the selected file + "Pick Hunks…" button. Description
 * fields (child on top, parent below — matching their order in the log) and options are at
 * the bottom.
 *
 * Ticking a file moves it to the new **child** commit; leaving it unticked keeps it in the
 * **parent** (whole-file path). Nothing is ticked by default. "Pick Hunks…" opens IDEA's
 * merge window to move a subset of a file's hunks to the child, leaving the remainder in
 * the parent.
 */
class SplitDialog(
    private val project: Project,
    private val sourceEntry: LogEntry,
    changes: List<Change>,
    preSelectedFiles: Set<FilePath>? = null
) : DialogWrapper(project) {
    var result: SplitSpec? = null
        private set

    private val allChanges = changes.toList()

    // --- Per-file loaded data cache (base content, after content, file type) ---
    private data class FileData(
        val afterContent: String,
        val baseContent: String, // null-safe: reverseApply result; empty string for added files
        val fileType: FileType
    )
    private val fileDataCache: MutableMap<FilePath, FileData> = LinkedHashMap()

    // --- Partial-file overrides: merge-picked first-commit content for partially-split files ---
    // Non-null entry = this file has a partial first-commit content (from the merge picker).
    private val firstCommitOverrides: MutableMap<FilePath, String> = LinkedHashMap()

    // --- File selection (left panel) ---
    internal val fileSelection = FileSelectionPanel(project)
    private var previousIncluded: Set<FilePath> = emptySet()

    // --- Right panel: native diff preview ---
    private val diffPreviewPanel: DiffRequestPanel =
        DiffManager.getInstance().createRequestPanel(project, disposable, null)
    private var currentPreviewFile: FilePath? = null

    // --- "Pick Hunks…" button ---
    internal val pickHunksButton = JButton(JujutsuBundle.message("dialog.split.pickHunks")).apply {
        isEnabled = false
        addActionListener { onPickHunks() }
    }

    // --- Descriptions ---
    internal val parentDescriptionField = JBTextArea(sourceEntry.description.actual, 2, 0)
    internal val childDescriptionField = JBTextArea(sourceEntry.description.actual, 2, 0)

    // --- Dynamic labels ---
    internal val parentHeaderLabel = JLabel()
    internal val childHeaderLabel = JLabel()
    private val parentDescriptionLabel = JLabel()
    private val childDescriptionLabel = JLabel()

    // Short labels for the two commits; match the merge picker and summary wording.
    internal var firstCommitLabel: String = legendLabel("dialog.split.legend.parent")
    internal var secondCommitLabel: String = legendLabel("dialog.split.legend.child")

    // --- Summary ---
    internal val summaryLabel = JBLabel().apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    // --- Options ---
    internal val parallelCheckBox = JBCheckBox(JujutsuBundle.message("dialog.split.parallel"))

    // Preview panel header.
    private val previewHeader = JBLabel(
        JujutsuBundle.message("dialog.split.preview.select"),
        javax.swing.SwingConstants.CENTER
    ).apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        font = font.deriveFont(Font.BOLD)
    }

    private val descriptionChangeListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) = Unit
        override fun removeUpdate(e: DocumentEvent?) = Unit
        override fun changedUpdate(e: DocumentEvent?) = Unit
    }

    // --- Test seam: injectable merge picker (avoids modal merge under tests) ---
    @org.jetbrains.annotations.TestOnly
    internal var hunkPickerForTest: ((FilePath) -> String?)? = null

    init {
        title = JujutsuBundle.message("dialog.split.title")
        setOKButtonText(JujutsuBundle.message("dialog.split.button"))

        parallelCheckBox.addActionListener { updateDynamicLabels() }
        updateDynamicLabels()

        // Populate file selection panel.
        // Checked/included files are the ones MOVING TO THE CHILD (the new, split-off commit);
        // everything left unticked stays in the parent. Nothing is ticked by default — the user
        // opts in to what gets split off. preSelectedFiles (e.g. right-clicked files via
        // "Split into New Child") start ticked, since that's what the user asked to split off.
        val initialIncludedPaths = preSelectedFiles ?: emptySet()
        fileSelection.setChanges(allChanges, allChanges.filter { it.filePath in initialIncludedPaths })
        previousIncluded = initialIncludedPaths

        // Listen for file checkbox changes.
        fileSelection.addInclusionListener { onFileInclusionChanged() }

        // Listen for file selection changes (to show diff preview for selected file).
        fileSelection.changesTree.addTreeSelectionListener(
            TreeSelectionListener {
                val selected = fileSelection.changesTree.selectedChanges.firstOrNull()
                if (selected != null) showPreviewForChange(selected)
            }
        )

        updateSummary()
        init()
    }

    // ---- File inclusion sync ----

    private fun onFileInclusionChanged() {
        val nowIncluded = fileSelection.includedChanges.mapNotNull { it.filePath }.toSet()

        // Files newly unticked → clear any partial override (file is fully in the parent).
        for (fp in (previousIncluded - nowIncluded)) {
            firstCommitOverrides.remove(fp)
        }
        // Files newly ticked → clear any partial override (file moves fully to the child).
        for (fp in (nowIncluded - previousIncluded)) {
            firstCommitOverrides.remove(fp)
        }

        previousIncluded = nowIncluded
        updateSummary()

        // Refresh preview if the currently-shown file's inclusion changed.
        currentPreviewFile?.let { fp ->
            val change = allChanges.find { it.filePath == fp }
            if (change != null) refreshPreview(fp)
        }
    }

    // ---- File diff loading + preview ----

    /**
     * Show the diff preview for [change], loading the diff lazily.
     */
    private fun showPreviewForChange(change: Change) {
        val fp = change.filePath ?: return
        currentPreviewFile = fp

        // Update header.
        previewHeader.text = fp.name
        previewHeader.foreground = JBUI.CurrentTheme.Label.foreground()

        // If already loaded, update the preview immediately.
        val cached = fileDataCache[fp]
        if (cached != null) {
            updateDiffPreview(fp, cached)
            return
        }

        // Clear while loading.
        diffPreviewPanel.setRequest(null)
        pickHunksButton.isEnabled = false

        val root = sourceEntry.repo.directory
        val relPath = fp.relativeTo(root)
        val revision = sourceEntry.id
        val executor = sourceEntry.repo.commandExecutor

        runInBackground(ModalityState.any()) {
            val afterResult = executor.show(fp, revision)
            val diffResult = executor.diffGitFile(revision, fp)

            val afterContent = if (afterResult.isSuccess) afterResult.stdout else null
            val gitDiff = diffResult.stdout

            // Derive base (parent) content from the diff.
            val baseContent = if (afterContent != null) {
                GitDiffReverseApplier.reverseApply(afterContent, gitDiff) ?: afterContent
            } else {
                null
            }

            val fileData = if (afterContent != null && baseContent != null) {
                val fileType = fileTypeFor(fp.name)
                FileData(afterContent = afterContent, baseContent = baseContent, fileType = fileType)
            } else {
                null
            }

            if (fileData != null) {
                fileDataCache[fp] = fileData
            }

            runLater {
                if (!isDisposed && currentPreviewFile == fp) {
                    if (fileData != null) {
                        updateDiffPreview(fp, fileData)
                    } else {
                        diffPreviewPanel.setRequest(null)
                        pickHunksButton.isEnabled = false
                    }
                    updateSummary()
                }
            }
        }
    }

    /**
     * Update the diff preview for [fp] using [data].
     *
     * The preview shows the **split-off change that moves to the child**: the right side is
     * always [FileData.afterContent] (the child's content — the child is the tip, so it always
     * holds the full original content). The left side reflects what **remains in the parent**:
     * - Ticked (moving to child): `baseContent` — nothing left for the parent.
     * - Partial (merge-picked override): the picked parent-remainder content.
     * - Unticked (stays in parent): `afterContent` — the parent keeps everything, so there's
     *   nothing left to move (left == right, empty diff).
     */
    private fun updateDiffPreview(fp: FilePath, data: FileData) {
        val isChild = fileSelection.includedChanges.any { it.filePath == fp }
        val override = firstCommitOverrides[fp]
        val leftContent = computePreviewLeftContent(isChild, override, data.baseContent, data.afterContent)

        val (leftTitle, rightTitle) = describeSplitState(
            leftContent,
            data.baseContent,
            data.afterContent,
            firstCommitLabel,
            secondCommitLabel
        )

        val leftDiffContent = makeContent(leftContent, data.fileType)
        val rightDiffContent = makeContent(data.afterContent, data.fileType)

        val request = SimpleDiffRequest(fp.name, leftDiffContent, rightDiffContent, leftTitle, rightTitle)
        diffPreviewPanel.setRequest(request)

        // Enable "Pick Hunks…" only for text files that have changes.
        pickHunksButton.isEnabled = data.baseContent != data.afterContent
    }

    /**
     * Compute the left-side (parent-remainder) content for the diff preview: an explicit
     * override wins, otherwise it's derived from whether the file is ticked to move to the
     * child (parent ends up empty) or stays put (parent keeps everything).
     * Extracted for test seaming; takes plain strings so the private [FileData] type is not exposed.
     */
    internal fun computePreviewLeftContent(
        isIncludedInChild: Boolean,
        override: String?,
        baseContent: String,
        afterContent: String
    ): String = when {
        override != null -> override
        isIncludedInChild -> baseContent
        else -> afterContent
    }

    private fun refreshPreview(fp: FilePath) {
        val data = fileDataCache[fp] ?: return
        updateDiffPreview(fp, data)
    }

    private fun makeContent(text: String, fileType: FileType): DiffContent {
        val content = DiffContentFactory.getInstance().create(project, text, fileType)
        content.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        return content
    }

    private fun fileTypeFor(name: String): FileType =
        FileTypeManager.getInstance().getFileTypeByFileName(name)
            .takeIf { it != com.intellij.openapi.fileTypes.UnknownFileType.INSTANCE }
            ?: PlainTextFileType.INSTANCE

    // ---- Hunk picker ----

    private fun onPickHunks() {
        val fp = currentPreviewFile ?: return
        val data = fileDataCache[fp] ?: return
        val isChild = fileSelection.includedChanges.any { it.filePath == fp }

        // Resume any existing partial pick; otherwise start from the tick-derived default.
        val initialContent = firstCommitOverrides[fp]
            ?: computePreviewLeftContent(isChild, null, data.baseContent, data.afterContent)

        val pickedContent: String? = hunkPickerForTest?.invoke(fp)
            ?: HunkDiffPicker.pickParentContent(
                project = project,
                fileName = fp.name,
                fileType = data.fileType,
                baseContent = data.baseContent,
                afterContent = data.afterContent,
                initialContent = initialContent,
                parentLabel = firstCommitLabel,
                childLabel = secondCommitLabel
            )

        if (pickedContent == null) return // user cancelled — keep prior state

        applyPickedContent(fp, pickedContent, data.baseContent, data.afterContent)
        refreshPreview(fp)
        updateSummary()
    }

    /**
     * Apply a hunk-picker result for [fp]. Fully-none/fully-all results are genuinely resolved
     * states and adjust the tick accordingly; anything else is a genuine partial, which stores
     * the parent-remainder override but **deliberately leaves the tick state untouched**.
     *
     * The tick is inert once an override exists — every downstream read of a file's content
     * (`doOKAction`, the preview) checks the override first. Force-ticking a partial file here
     * previously made a half-picked file look fully committed to the child, which wasn't true;
     * the half-checked render (`partialChanges`, synced by the caller's `updateSummary()`) is
     * what should communicate "partial" to the user, not the tick.
     */
    internal fun applyPickedContent(fp: FilePath, pickedContent: String, baseContent: String, afterContent: String) {
        when (pickedContent) {
            baseContent -> {
                // Nothing left for the parent → file fully moved to child, tick it.
                firstCommitOverrides.remove(fp)
                ensureFileIncluded(fp)
            }
            afterContent -> {
                // Parent keeps everything → nothing moved to child, untick it.
                firstCommitOverrides.remove(fp)
                ensureFileExcluded(fp)
            }
            else -> {
                firstCommitOverrides[fp] = pickedContent
            }
        }
    }

    private fun ensureFileIncluded(fp: FilePath) {
        val change = allChanges.find { it.filePath == fp } ?: return
        val current = fileSelection.includedChanges.toMutableList()
        if (change !in current) {
            current.add(change)
            fileSelection.changesTree.setIncludedChanges(current)
            previousIncluded = current.mapNotNull { it.filePath }.toSet()
        }
    }

    private fun ensureFileExcluded(fp: FilePath) {
        val change = allChanges.find { it.filePath == fp } ?: return
        val current = fileSelection.includedChanges.toMutableList()
        if (change in current) {
            current.remove(change)
            fileSelection.changesTree.setIncludedChanges(current)
            previousIncluded = current.mapNotNull { it.filePath }.toSet()
        }
    }

    // ---- Dynamic labels ----

    private fun updateDynamicLabels() {
        val parallel = parallelCheckBox.isSelected

        firstCommitLabel = legendLabel(if (parallel) "dialog.split.legend.second" else "dialog.split.legend.parent")
        secondCommitLabel = legendLabel(if (parallel) "dialog.split.legend.first" else "dialog.split.legend.child")

        parentHeaderLabel.text = JujutsuBundle.message(
            if (parallel) "dialog.split.parent.header.parallel" else "dialog.split.parent.header"
        )
        parentHeaderLabel.font = parentHeaderLabel.font.deriveFont(Font.BOLD)

        childHeaderLabel.text = JujutsuBundle.message(
            if (parallel) "dialog.split.child.header.parallel" else "dialog.split.child.header"
        )
        childHeaderLabel.font = childHeaderLabel.font.deriveFont(Font.BOLD)

        parentDescriptionLabel.text = JujutsuBundle.message(
            if (parallel) "dialog.split.parent.description.parallel" else "dialog.split.parent.description"
        )
        childDescriptionLabel.text = JujutsuBundle.message(
            if (parallel) "dialog.split.child.description.parallel" else "dialog.split.child.description"
        )
    }

    private fun updateSummary() {
        val childFiles = fileSelection.includedChanges.size // ticked = moving to child
        val totalFiles = allChanges.size
        val parentFiles = totalFiles - childFiles
        val partialCount = firstCommitOverrides.size

        // Partial files contribute hunks to both commits, so they appear in both counts.
        // Child first, matching its position above the parent in the log.
        summaryLabel.text = if (partialCount > 0) {
            JujutsuBundle.message(
                "dialog.split.summary.partial",
                secondCommitLabel,
                childFiles,
                partialCount,
                firstCommitLabel,
                parentFiles + partialCount
            )
        } else {
            JujutsuBundle.message(
                "dialog.split.summary",
                secondCommitLabel,
                childFiles,
                firstCommitLabel,
                parentFiles
            )
        }

        // Sync partial-change set into the tree so partial files render as half-checked.
        val partialChangeObjects = allChanges.filter { it.filePath in firstCommitOverrides }.toSet()
        fileSelection.setPartialChanges(partialChangeObjects)
    }

    // ---- Layout ----

    override fun createCenterPanel(): JComponent {
        val leftPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 8, 0, 4)
            add(createSourceSection(), BorderLayout.NORTH)
            add(createFilesSection(), BorderLayout.CENTER)
        }

        val rightPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 4, 0, 8)
            add(previewHeader, BorderLayout.NORTH)
            add(diffPreviewPanel.component, BorderLayout.CENTER)
            add(createPickHunksBar(), BorderLayout.SOUTH)
        }

        val splitter = OnePixelSplitter(false, 0.4f).apply {
            firstComponent = leftPanel
            secondComponent = rightPanel
        }

        val outer = JPanel(BorderLayout())
        outer.add(splitter, BorderLayout.CENTER)
        outer.add(createBottomSection(), BorderLayout.SOUTH)
        outer.preferredSize = Dimension(JBUI.scale(960), JBUI.scale(600))
        return outer
    }

    private fun createPickHunksBar(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(4, 0, 0, 0)
        add(pickHunksButton)
        add(Box.createHorizontalGlue())
    }

    private fun createSourceSection() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(createSectionLabel(JujutsuBundle.message("dialog.split.source")))
        add(createEntryPane(sourceEntry))
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(createSectionLabel(JujutsuBundle.message("dialog.split.files")))
    }

    private fun createFilesSection() = JPanel(BorderLayout()).apply {
        add(fileSelection, BorderLayout.CENTER)
        val footerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(4, 0)
            add(summaryLabel)
            add(Box.createHorizontalGlue())
        }
        add(footerPanel, BorderLayout.SOUTH)
    }

    private fun createBottomSection(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        // Child description first — matches the child's position above the parent in the log.
        childHeaderLabel.alignmentX = JLabel.LEFT_ALIGNMENT
        panel.add(childHeaderLabel)
        childDescriptionLabel.alignmentX = JLabel.LEFT_ALIGNMENT
        panel.add(childDescriptionLabel)
        val childScroll = JBScrollPane(childDescriptionField).apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
            preferredSize = Dimension(0, JBUI.scale(46))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(46))
        }
        panel.add(childScroll)

        panel.add(Box.createVerticalStrut(JBUI.scale(6)))

        // Parent description.
        parentHeaderLabel.alignmentX = JLabel.LEFT_ALIGNMENT
        panel.add(parentHeaderLabel)
        parentDescriptionLabel.alignmentX = JLabel.LEFT_ALIGNMENT
        panel.add(parentDescriptionLabel)
        val parentScroll = JBScrollPane(parentDescriptionField).apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
            preferredSize = Dimension(0, JBUI.scale(46))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(46))
        }
        panel.add(parentScroll)

        panel.add(Box.createVerticalStrut(JBUI.scale(4)))

        // Parallel checkbox.
        parallelCheckBox.alignmentX = JPanel.LEFT_ALIGNMENT
        panel.add(parallelCheckBox)

        return panel
    }

    // ---- Helpers ----

    private fun createSectionLabel(text: String) = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
        alignmentX = JLabel.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(4, 0)
    }

    private fun createEntryPane(entry: LogEntry) = IconAwareHtmlPane(project).apply {
        alignmentX = JPanel.LEFT_ALIGNMENT
        text = htmlString {
            appendStatusIndicators(entry)
            append(entry.id)
            append(" ")
            appendDescriptionAndEmptyIndicator(entry)
            append(" ")
            appendDecorations(entry)
        }
    }

    // ---- Validation ----

    override fun doValidate(): ValidationInfo? {
        val included = fileSelection.includedChanges // ticked = moving to child
        val total = allChanges.size

        if (included.isEmpty()) {
            val key = if (parallelCheckBox.isSelected) {
                "dialog.split.validation.child.empty.parallel"
            } else {
                "dialog.split.validation.child.empty"
            }
            return ValidationInfo(JujutsuBundle.message(key), fileSelection.changesTree)
        }
        if (included.size == total && firstCommitOverrides.isEmpty()) {
            val key = if (parallelCheckBox.isSelected) {
                "dialog.split.validation.parent.empty.parallel"
            } else {
                "dialog.split.validation.parent.empty"
            }
            return ValidationInfo(JujutsuBundle.message(key), fileSelection.changesTree)
        }
        return null
    }

    // ---- OK action ----

    override fun doOKAction() {
        // Ticked changes move to the child; everything else stays in the parent.
        val childChanges = fileSelection.includedChanges.toList()
        val childFilePaths = childChanges.mapNotNull { it.filePath }.toSet()
        val parentPaths = allChanges.mapNotNull { it.filePath }.filter { it !in childFilePaths }

        val hunkSelection: SplitHunkSelection? = if (firstCommitOverrides.isNotEmpty()) {
            // Build FileFirstCommit (parent-remainder content) for every changed file.
            val files = allChanges.mapNotNull { change ->
                val fp = change.filePath ?: return@mapNotNull null
                val root = sourceEntry.repo.directory
                val relPath = fp.relativeTo(root)
                val override = firstCommitOverrides[fp]
                val isChild = fp in childFilePaths
                val content: String? = when {
                    override != null -> override // partial via merge picker
                    isChild -> null // whole file moves to child → absent from first/parent commit
                    else -> fileDataCache[fp]?.afterContent // whole file stays in parent
                }
                FileFirstCommit(relPath = relPath, filePath = fp, content = content)
            }
            SplitHunkSelection(files)
        } else {
            null
        }

        val parentDesc = parentDescriptionField.text.trim()
        val childDesc = childDescriptionField.text.trim()
        val originalDesc = sourceEntry.description.actual

        result = SplitSpec(
            revision = sourceEntry.id,
            filePaths = parentPaths,
            hunkSelection = hunkSelection,
            description = Description(parentDesc),
            childDescription = if (childDesc != originalDesc) Description(childDesc) else null,
            parallel = parallelCheckBox.isSelected
        )
        super.doOKAction()
    }

    // ---- Test seams ----

    /** Set a merge-picker override for a file directly (for testing without the modal merge). */
    @org.jetbrains.annotations.TestOnly
    internal fun setFirstCommitOverrideForTest(filePath: FilePath, content: String?) {
        if (content != null) {
            firstCommitOverrides[filePath] = content
        } else {
            firstCommitOverrides.remove(filePath)
        }
        updateSummary()
    }

    /** Trigger OK action without showing the dialog (for testing). */
    @org.jetbrains.annotations.TestOnly
    internal fun performOKForTest() = doOKAction()

    /** Run validation and return the result (for testing). */
    @org.jetbrains.annotations.TestOnly
    internal fun doValidateForTest() = doValidate()

    /** Current parent description text (for testing). */
    internal val parentDescriptionText: String get() = parentDescriptionField.text

    /** Current child description text (for testing). */
    internal val childDescriptionText: String get() = childDescriptionField.text
}

/** Capitalize a legend bundle key value (e.g. "parent" → "Parent"). */
private fun legendLabel(key: String) =
    JujutsuBundle.message(key).replaceFirstChar { it.uppercaseChar() }

/**
 * Describe the split state of [content] (relative to [baseContent]/[afterContent]) as a pair
 * of (parent title, child title) label fragments, for the main file preview's diff titles —
 * e.g. an untouched (unticked) file reads "Parent (all changes)" / "Child (no changes)"; a
 * fully-moved (ticked) file reads "Parent (unchanged)" / "Child (all changes)"; anything else
 * is "partial".
 */
internal fun describeSplitState(
    content: String,
    baseContent: String,
    afterContent: String,
    parentLabel: String,
    childLabel: String
): Pair<String, String> = when (content) {
    afterContent -> Pair(
        JujutsuBundle.message("dialog.split.hunks.parent.allChanges", parentLabel),
        JujutsuBundle.message("dialog.split.hunks.child.noChanges", childLabel)
    )
    baseContent -> Pair(
        JujutsuBundle.message("dialog.split.hunks.parent.unchanged", parentLabel),
        JujutsuBundle.message("dialog.split.hunks.child.allChanges", childLabel)
    )
    else -> Pair(
        JujutsuBundle.message("dialog.split.hunks.parent.partial", parentLabel),
        JujutsuBundle.message("dialog.split.hunks.child.partial", childLabel)
    )
}
