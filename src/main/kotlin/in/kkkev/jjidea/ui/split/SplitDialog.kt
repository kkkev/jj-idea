package `in`.kkkev.jjidea.ui.split

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.diffedit.HunkPicker
import `in`.kkkev.jjidea.diffedit.HunkPickerLabels
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.ui.common.DiffPane
import `in`.kkkev.jjidea.ui.common.FileContents
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import `in`.kkkev.jjidea.ui.common.HunkPickPreviewController
import `in`.kkkev.jjidea.ui.common.HunkSelection
import `in`.kkkev.jjidea.ui.common.buildHunkSelection
import `in`.kkkev.jjidea.ui.common.createSourcePanel
import `in`.kkkev.jjidea.ui.components.DescriptionEditor
import `in`.kkkev.jjidea.util.GitDiffReverseApplier
import `in`.kkkev.jjidea.vcs.filePath
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

/**
 * Result of the split dialog.
 *
 * The fileset that ends up in [filePaths] is always the ticked pane's files when [newParent] mode
 * built this spec, and the *unticked* pane's files otherwise (`jj split`'s "selected" fileset
 * argument) - see [SplitDialog] for why the tick-to-fileset mapping depends on mode.
 *
 * [selectedDescription] is passed as `-m` and always describes whichever side [filePaths] ends up
 * in (`jj split`'s "selected" side). [remainingDescription] is applied via a follow-up
 * `jj describe` on whichever side is *not* [filePaths] (`jj split`'s "remaining" side; null = keep
 * its original description). [insertBefore] is non-null only in [newParent] mode: it becomes
 * `jj split`'s `-B` argument, extracting [filePaths] into a **new** commit inserted before
 * [insertBefore], while the remaining changes keep [revision]'s original change ID and location.
 * When null (the default, "Split into New Child" mode), [filePaths] stays on [revision]'s
 * original change ID and everything else becomes a new child commit instead.
 */
data class SplitSpec(
    val revision: Revision,
    /** Whole-file fast path: see this class's KDoc for which side's files end up here. */
    val filePaths: List<FilePath>,
    /** Hunk-level selection. Non-null when at least one file is partially selected. */
    val hunkSelection: HunkSelection?,
    val selectedDescription: Description,
    val remainingDescription: Description?,
    val parallel: Boolean,
    val insertBefore: Revision? = null
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
 *
 * [newParent] (jj-idea-tkog, GitHub #74) switches the whole-file fast path to `jj split -B`
 * instead of the plain no-flag default: ticking a file now moves it to a **new commit inserted
 * as [sourceEntry]'s parent**, while unticked files **stay on [sourceEntry]'s own change ID and
 * location** - the polarity flip real jj applies once `-B`/`-A`/`-o` is given (verified against
 * jj 0.44: the fileset passed to `jj split` becomes the *new* commit under `-B`, whereas without
 * any placement flag it's the side that *keeps* the original identity). The "Parent"/"Child"
 * header/legend wording from the default mode would be backwards here, so [updateDynamicLabels]
 * switches to neutral "New commit"/"Stays here" wording instead when [newParent] is set. Hunk-level
 * partial selection ("Pick Hunks…") is disabled in this mode - its content-polarity math is tied
 * to the default mode's fileset-role assumption and hasn't been verified against `-B`.
 */
class SplitDialog(
    private val project: Project,
    private val sourceEntry: LogEntry,
    changes: List<Change>,
    preSelectedFiles: Set<FilePath>? = null,
    private val newParent: Boolean = false
) : DialogWrapper(project) {
    var result: SplitSpec? = null
        private set

    private val allChanges = changes.toList()

    // --- Partial-file overrides: merge-picked first-commit content for partially-split files ---
    // Non-null entry = this file has a partial first-commit content (from the merge picker).
    private val firstCommitOverrides: MutableMap<FilePath, String> = LinkedHashMap()

    // --- File selection (left panel) ---
    internal val fileSelection = FileSelectionPanel(project)
    private var previousIncluded: Set<FilePath> = emptySet()

    // --- Right panel: native diff preview, cache + lazy-load shared with SquashIntoDialog ---
    private val previewController = HunkPickPreviewController(
        project = project,
        disposable = disposable,
        loadContents = ::loadFileContents,
        resolveContent = { fp, included, contents ->
            firstCommitOverrides[fp] ?: computePreviewLeftContent(included, null, contents.before, contents.after)
        },
        previewPanes = { content, contents ->
            splitPreviewPanes(content, contents, firstCommitLabel, secondCommitLabel)
        },
        isIncluded = { fp -> fileSelection.includedChanges.any { it.filePath == fp } }
    )
    private val diffPreview get() = previewController.preview

    // --- "Pick Hunks…" button ---
    // Hidden entirely in newParent mode - see class KDoc for why partial hunk selection isn't
    // supported there.
    internal val pickHunksButton = previewController.pickHunksButton.apply {
        isVisible = !newParent
        addActionListener { onPickHunks() }
    }

    // --- Descriptions ---
    internal val parentDescriptionEditor = DescriptionEditor(project).apply {
        text = sourceEntry.description
        Disposer.register(disposable, this)
    }
    internal val childDescriptionEditor = DescriptionEditor(project).apply {
        text = sourceEntry.description
        Disposer.register(disposable, this)
    }

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
    // `jj split` rejects -B combined with --parallel, so this is unavailable in newParent mode.
    internal val parallelCheckBox = JBCheckBox(JujutsuBundle.message("dialog.split.parallel")).apply {
        isVisible = !newParent
    }

    // --- Working-copy movement note (jj-idea-tkog) ---
    // Only shown when splitting the working copy itself, where which side @ ends up on isn't
    // obvious: it stays on the original change ID in newParent mode, but moves to the new child
    // commit in the default mode.
    private val workingCopyNoteLabel = JBLabel().apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        isVisible = sourceEntry.isWorkingCopy
        if (sourceEntry.isWorkingCopy) {
            text = JujutsuBundle.message(
                if (newParent) "dialog.split.wc.stays" else "dialog.split.wc.moves",
                sourceEntry.id.short
            )
        }
    }

    // --- Test seam: injectable merge picker (avoids modal merge under tests) ---
    @org.jetbrains.annotations.TestOnly
    internal var hunkPickerForTest: ((FilePath) -> String?)? = null

    init {
        title = JujutsuBundle.message(if (newParent) "dialog.split.title.newParent" else "dialog.split.title")
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
        fileSelection.changesTree.addTreeSelectionListener {
            val selected = fileSelection.changesTree.selectedChanges.firstOrNull()
            if (selected != null) previewController.showFor(selected)
        }

        updateSummary()
        init()
    }

    // ---- File inclusion sync ----

    private fun onFileInclusionChanged() {
        val nowIncluded = fileSelection.includedChanges.map { it.filePath }.toSet()

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
        previewController.currentFile?.let { fp ->
            val change = allChanges.find { it.filePath == fp }
            if (change != null) previewController.refresh(fp)
        }
    }

    // ---- File diff loading + preview ----

    /**
     * Load the split-off change's before/after content and file type for [change], off the EDT —
     * the [HunkPickPreviewController] loader.
     *
     * The preview shows the **split-off change that moves to the child**: the right (Child) side
     * is always the child's full content (the child is the tip, so it always holds the full
     * original content — see [splitPreviewPanes]). The left (Parent) side reflects what
     * **remains in the parent** — see [computePreviewLeftContent].
     */
    private fun loadFileContents(change: Change): FileContents? {
        val fp = change.filePath
        val revision = sourceEntry.id
        val executor = sourceEntry.repo.commandExecutor

        val afterResult = executor.show(fp, revision)
        val diffResult = executor.diffGitFile(revision, fp)

        val afterContent = if (afterResult is CommandExecutor.CommandResult.Success) afterResult.stdout else null
        val gitDiff = diffResult.stdout

        // Derive base (parent) content from the diff.
        val baseContent = if (afterContent != null) {
            GitDiffReverseApplier.reverseApply(afterContent, gitDiff) ?: afterContent
        } else {
            null
        }

        if (afterContent == null || baseContent == null) return null
        return FileContents(before = baseContent, after = afterContent, fileType = HunkPicker.fileTypeFor(fp.name))
    }

    /**
     * Compute the left-side (parent-remainder) content for the diff preview: an explicit
     * override wins, otherwise it's derived from whether the file is ticked to move to the
     * child (parent ends up empty) or stays put (parent keeps everything).
     * Extracted for test seaming; takes plain strings so [FileContents] is not exposed.
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

    // ---- Hunk picker ----

    private fun onPickHunks() {
        val fp = previewController.currentFile ?: return
        val data = previewController.cachedContents(fp) ?: return
        val isChild = fileSelection.includedChanges.any { it.filePath == fp }

        // Resume any existing partial pick; otherwise start from the tick-derived default.
        val initialContent = firstCommitOverrides[fp]
            ?: computePreviewLeftContent(isChild, null, data.before, data.after)

        val pickedContent: String? = hunkPickerForTest?.invoke(fp)
            ?: HunkPicker.pickRemainderContent(
                project = project,
                fileName = fp.name,
                fileType = data.fileType,
                baseContent = data.before,
                afterContent = data.after,
                initialContent = initialContent,
                labels = HunkPickerLabels.forSplit(firstCommitLabel, secondCommitLabel)
            )

        if (pickedContent == null) return // user cancelled — keep prior state

        applyPickedContent(fp, pickedContent, data.before, data.after)
        previewController.refresh(fp)
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
        fileSelection.setIncluded(change, true)
        previousIncluded = fileSelection.includedChanges.map { it.filePath }.toSet()
    }

    private fun ensureFileExcluded(fp: FilePath) {
        val change = allChanges.find { it.filePath == fp } ?: return
        fileSelection.setIncluded(change, false)
        previousIncluded = fileSelection.includedChanges.map { it.filePath }.toSet()
    }

    // ---- Dynamic labels ----

    private fun updateDynamicLabels() {
        if (newParent) {
            // Neutral wording: the ticked pane always ends up as the new commit here, and the
            // unticked pane always keeps sourceEntry's own change ID - "Parent"/"Child" from the
            // default mode would say the opposite of what actually happens (see class KDoc).
            firstCommitLabel = legendLabel("dialog.split.legend.stays")
            secondCommitLabel = legendLabel("dialog.split.legend.new")

            parentHeaderLabel.text = JujutsuBundle.message("dialog.split.stays.header", sourceEntry.id.short)
            parentHeaderLabel.font = parentHeaderLabel.font.deriveFont(Font.BOLD)

            childHeaderLabel.text = JujutsuBundle.message("dialog.split.new.header", sourceEntry.id.short)
            childHeaderLabel.font = childHeaderLabel.font.deriveFont(Font.BOLD)

            parentDescriptionLabel.text = JujutsuBundle.message("dialog.split.stays.description")
            childDescriptionLabel.text = JujutsuBundle.message("dialog.split.new.description")
            return
        }

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
            add(diffPreview, BorderLayout.CENTER)
        }
        diffPreview.addFooterComponent(pickHunksButton)

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

    private fun createSourceSection() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(createSectionLabel(JujutsuBundle.message("dialog.split.source")))
        add(createEntryPane(sourceEntry))
        if (workingCopyNoteLabel.isVisible) {
            workingCopyNoteLabel.alignmentX = JLabel.LEFT_ALIGNMENT
            add(workingCopyNoteLabel)
        }
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

        val childBlock = descriptionBlock(childHeaderLabel, childDescriptionLabel, childDescriptionEditor)
        val parentBlock = descriptionBlock(parentHeaderLabel, parentDescriptionLabel, parentDescriptionEditor)

        if (newParent) {
            // parentHeaderLabel/parentDescriptionEditor is the *unticked* pane here, i.e. the
            // "Stays here" side - shown first (top) since it occupies the more-recent, unmoved
            // position, matching where it already sits in the log; childHeaderLabel/
            // childDescriptionEditor ("New commit") is the newly-inserted *older* parent, shown
            // second (bottom) to match its position one row further down the log.
            panel.add(parentBlock)
            panel.add(Box.createVerticalStrut(JBUI.scale(6)))
            panel.add(childBlock)
        } else {
            // Child description first — matches the child's position above the parent in the log.
            panel.add(childBlock)
            panel.add(Box.createVerticalStrut(JBUI.scale(6)))
            panel.add(parentBlock)
        }

        panel.add(Box.createVerticalStrut(JBUI.scale(4)))

        // Parallel checkbox.
        parallelCheckBox.alignmentX = JPanel.LEFT_ALIGNMENT
        panel.add(parallelCheckBox)

        return panel
    }

    private fun descriptionBlock(header: JLabel, description: JLabel, editor: DescriptionEditor): JPanel {
        header.alignmentX = JLabel.LEFT_ALIGNMENT
        description.alignmentX = JLabel.LEFT_ALIGNMENT
        // CommitMessage scrolls itself - no JBScrollPane wrapper needed, unlike the old JBTextArea.
        editor.component.apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
            preferredSize = Dimension(0, JBUI.scale(46))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(46))
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JPanel.LEFT_ALIGNMENT
            add(header)
            add(description)
            add(editor.component)
        }
    }

    // ---- Helpers ----

    private fun createSectionLabel(text: String) = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
        alignmentX = JLabel.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(4, 0)
    }

    private fun createEntryPane(entry: LogEntry) = createSourcePanel(project, listOf(entry))

    // ---- Validation ----

    override fun doValidate(): ValidationInfo? {
        val included = fileSelection.includedChanges // ticked = moving to the new commit
        val total = allChanges.size

        if (included.isEmpty()) {
            val key = when {
                newParent -> "dialog.split.validation.new.empty"
                parallelCheckBox.isSelected -> "dialog.split.validation.child.empty.parallel"
                else -> "dialog.split.validation.child.empty"
            }
            return ValidationInfo(JujutsuBundle.message(key), fileSelection.changesTree)
        }
        if (included.size == total && firstCommitOverrides.isEmpty()) {
            val key = when {
                newParent -> "dialog.split.validation.stays.empty"
                parallelCheckBox.isSelected -> "dialog.split.validation.parent.empty.parallel"
                else -> "dialog.split.validation.parent.empty"
            }
            return ValidationInfo(JujutsuBundle.message(key), fileSelection.changesTree)
        }
        return null
    }

    // ---- OK action ----

    override fun doOKAction() {
        val ticked = fileSelection.includedChanges.toList()
        val tickedPaths = ticked.map { it.filePath }.toSet()

        // Which pane's files become the fileset passed to `jj split` (its "selected" argument)
        // depends on mode: in newParent mode, `-B` makes the ticked pane the new commit; in the
        // default mode, the unticked pane keeps the original ID and is what gets passed instead
        // (see SplitSpec's KDoc).
        val selectedPaths = if (newParent) {
            ticked.map { it.filePath }
        } else {
            allChanges.map { it.filePath }.filter { it !in tickedPaths }
        }

        val hunkSelection: HunkSelection? = if (!newParent && firstCommitOverrides.isNotEmpty()) {
            // Build the parent-remainder content for every changed file.
            // newParent mode never reaches here - "Pick Hunks…" is hidden in that mode.
            // Deletion-manifest handling is deferred here (isDeletion always false) - see
            // jj-idea-4q7m's follow-up bead for Split's symmetric gap (an *unticked* deletion
            // should land in the first commit, which today just writes an empty file instead).
            buildHunkSelection(
                changes = allChanges,
                root = sourceEntry.repo.directory,
                overrides = firstCommitOverrides,
                isIncluded = { it in tickedPaths },
                isDeletion = { false },
                contentFor = { change, included ->
                    if (included) null else previewController.cachedContents(change.filePath)?.after
                }
            )
        } else {
            null
        }

        val parentFieldText = parentDescriptionEditor.text.actual.trim()
        val childFieldText = childDescriptionEditor.text.actual.trim()
        val originalDesc = sourceEntry.description.actual

        // Route by role, not by pane: the selected side (see selectedPaths above) always gets
        // -m; the remaining side always gets the follow-up describe.
        val selectedFieldText = if (newParent) childFieldText else parentFieldText
        val remainingFieldText = if (newParent) parentFieldText else childFieldText

        result = SplitSpec(
            revision = sourceEntry.id,
            filePaths = selectedPaths,
            hunkSelection = hunkSelection,
            selectedDescription = Description(selectedFieldText),
            remainingDescription = if (remainingFieldText != originalDesc) Description(remainingFieldText) else null,
            parallel = if (newParent) false else parallelCheckBox.isSelected,
            insertBefore = if (newParent) sourceEntry.id else null
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
    internal val parentDescriptionText: String get() = parentDescriptionEditor.text.actual

    /** Current child description text (for testing). */
    internal val childDescriptionText: String get() = childDescriptionEditor.text.actual
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

/**
 * The (left, right) [DiffPane]s for the main file preview: left is [content] itself (the
 * parent-remainder — see [SplitDialog.computePreviewLeftContent]), right is always
 * [FileContents.after] (the child is the tip of the split, so it always holds the full original
 * content). Titles come from [describeSplitState], evaluated on the same [content] that decides
 * the left pane's text, so a pane's text and its own title can never disagree (jj-idea-jb2q,
 * GitHub #101).
 */
internal fun splitPreviewPanes(
    content: String,
    contents: FileContents,
    parentLabel: String,
    childLabel: String
): Pair<DiffPane, DiffPane> {
    val (parentTitle, childTitle) = describeSplitState(
        content,
        contents.before,
        contents.after,
        parentLabel,
        childLabel
    )
    return Pair(DiffPane(content, parentTitle), DiffPane(contents.after, childTitle))
}
