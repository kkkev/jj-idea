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
import `in`.kkkev.jjidea.diffedit.HunkMergePicker
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
 * [filePaths] are the files for the **parent/first** commit when [hunkSelection] is null
 * (whole-file fast path). When [hunkSelection] is non-null, at least one file has partial
 * hunk selection and the split must go through the diff-editor path.
 *
 * [childDescription] is applied via `jj describe` after split (null = keep original).
 */
data class SplitSpec(
    val revision: Revision,
    /** Whole-file fast path: files for the first commit (empty = all). Ignored when hunkSelection != null. */
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
 * fields and options are at the bottom.
 *
 * Checking/unchecking a file sets it for the first or second commit (whole-file path).
 * "Pick Hunks…" opens IDEA's merge window; the merged result becomes that file's first-commit
 * content, with the remainder going to the second commit.
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
        val initialIncluded = if (preSelectedFiles != null) {
            allChanges.filter { it.filePath in preSelectedFiles }
        } else {
            allChanges
        }
        fileSelection.setChanges(allChanges, initialIncluded)
        previousIncluded = initialIncluded.mapNotNull { it.filePath }.toSet()

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

        // Files newly excluded → clear any partial override (file is fully in second commit).
        for (fp in (previousIncluded - nowIncluded)) {
            firstCommitOverrides.remove(fp)
        }
        // Files newly included → clear any partial override (file is fully in first commit).
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
     * The right side reflects the **actual first-commit content** for this file:
     * - Excluded (unticked): `baseContent` — no change goes to the first commit.
     * - Partial (merge-picked override): the picked content.
     * - Fully included: `afterContent` — all changes go to the first commit.
     */
    private fun updateDiffPreview(fp: FilePath, data: FileData) {
        val isIncluded = fileSelection.includedChanges.any { it.filePath == fp }
        val override = firstCommitOverrides[fp]

        val (rightContent, rightTitle) = computePreviewRight(
            isIncluded,
            override,
            data.baseContent,
            data.afterContent,
            firstCommitLabel
        )

        val leftContent = makeContent(data.baseContent, data.fileType)
        val rightDiffContent = makeContent(rightContent, data.fileType)

        val request = SimpleDiffRequest(
            fp.name,
            leftContent,
            rightDiffContent,
            JujutsuBundle.message("dialog.split.merge.side.original"),
            rightTitle
        )
        diffPreviewPanel.setRequest(request)

        // Enable "Pick Hunks…" only for text files that have changes.
        pickHunksButton.isEnabled = data.baseContent != data.afterContent
    }

    /**
     * Compute the right-side (first-commit) content and title for the diff preview.
     * Extracted for test seaming; takes plain strings so the private [FileData] type is not exposed.
     *
     * [commitLabel] is the short name of the target commit as shown in the dialog header
     * (e.g. "Parent" or "Second") so the panel title stays consistent with the file list.
     */
    internal fun computePreviewRight(
        isIncluded: Boolean,
        override: String?,
        baseContent: String,
        afterContent: String,
        commitLabel: String = legendLabel("dialog.split.legend.parent")
    ): Pair<String, String> = when {
        !isIncluded -> Pair(
            baseContent,
            JujutsuBundle.message("dialog.split.merge.side.firstCommit.unchanged", commitLabel)
        )
        override != null -> Pair(
            override,
            JujutsuBundle.message("dialog.split.merge.side.firstCommit", commitLabel)
        )
        else -> Pair(
            afterContent,
            JujutsuBundle.message("dialog.split.merge.side.changes")
        )
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

    // ---- Merge picker ----

    private fun onPickHunks() {
        val fp = currentPreviewFile ?: return
        val data = fileDataCache[fp] ?: return

        val pickedContent: String? = hunkPickerForTest?.invoke(fp)
            ?: HunkMergePicker.pickFirstCommitContent(
                project = project,
                fileName = fp.name,
                fileType = data.fileType,
                baseContent = data.baseContent,
                afterContent = data.afterContent,
                commitLabel = firstCommitLabel
            )

        if (pickedContent == null) return // user cancelled — keep prior state

        when (pickedContent) {
            data.afterContent -> {
                // User accepted all changes → file fully in first commit, clear override.
                firstCommitOverrides.remove(fp)
                ensureFileIncluded(fp)
            }
            data.baseContent -> {
                // User accepted no changes → file fully in second commit, uncheck.
                firstCommitOverrides.remove(fp)
                ensureFileExcluded(fp)
            }
            else -> {
                // Partial selection → store the override, keep file checked.
                firstCommitOverrides[fp] = pickedContent
                ensureFileIncluded(fp)
            }
        }

        refreshPreview(fp)
        updateSummary()
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
        val includedFiles = fileSelection.includedChanges.size
        val totalFiles = allChanges.size
        val excludedFiles = totalFiles - includedFiles
        val partialCount = firstCommitOverrides.size

        // Partial files contribute hunks to both commits, so they appear in both counts.
        summaryLabel.text = if (partialCount > 0) {
            JujutsuBundle.message(
                "dialog.split.summary.partial",
                firstCommitLabel,
                includedFiles,
                partialCount,
                secondCommitLabel,
                excludedFiles + partialCount
            )
        } else {
            JujutsuBundle.message(
                "dialog.split.summary",
                firstCommitLabel,
                includedFiles,
                secondCommitLabel,
                excludedFiles
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

        panel.add(Box.createVerticalStrut(JBUI.scale(6)))

        // Child description.
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
        val included = fileSelection.includedChanges
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
        val includedChanges = fileSelection.includedChanges.toList()
        val includedPaths = includedChanges.mapNotNull { it.filePath }

        val hunkSelection: SplitHunkSelection? = if (firstCommitOverrides.isNotEmpty()) {
            // Build FileFirstCommit for every changed file.
            val files = allChanges.mapNotNull { change ->
                val fp = change.filePath ?: return@mapNotNull null
                val root = sourceEntry.repo.directory
                val relPath = fp.relativeTo(root)
                val override = firstCommitOverrides[fp]
                val isIncluded = includedChanges.any { it.filePath == fp }
                val content: String? = when {
                    override != null -> override // partial via merge picker
                    isIncluded -> fileDataCache[fp]?.afterContent // whole file
                    else -> null // excluded → absent from first commit
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
            filePaths = includedPaths,
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
