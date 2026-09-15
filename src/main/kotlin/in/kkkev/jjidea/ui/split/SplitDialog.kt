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
import `in`.kkkev.jjidea.jj.ChangeId
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
import `in`.kkkev.jjidea.ui.components.IconAwareHtmlPane
import `in`.kkkev.jjidea.ui.components.append
import `in`.kkkev.jjidea.ui.components.htmlText
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
 * fields and options are at the bottom.
 *
 * **The tick has one meaning in every mode** (jj-idea-8khi, GitHub #101 UX follow-up): leaving a
 * file unticked keeps it **on [sourceEntry]'s own change ID and position** ("Stays on …");
 * ticking it moves it to a **brand-new commit** ("New commit …"). Only the new commit's
 * *position* changes with mode - a child (default), a sibling (`--parallel`, jj rebases any
 * existing children onto both siblings, turning them into merges), or a parent (`-B`/[newParent],
 * jj-idea-tkog, GitHub #74). Nothing is ticked by default. "Pick Hunks…" opens IDEA's merge
 * window to move a subset of a file's hunks to the new commit, leaving the remainder where it
 * stays; disabled in [newParent] mode, where its content-polarity math hasn't been verified
 * against `-B`.
 *
 * This identity-first wording is deliberately mode-invariant in the UI even though jj's own
 * fileset-argument polarity is not: the fileset passed on the command line is whichever side
 * *keeps* [sourceEntry]'s identity in the default and `--parallel` modes, but the side that
 * becomes the *new* commit under `-B` (verified against jj 0.44 - see [MutatingCommandsContractTest]
 * for the `--parallel` and `-B` contract tests this rests on). [updateDynamicLabels] and
 * [doOKAction] are what translate the constant tick meaning into that varying fileset role.
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
    // One label per description editor, not two (jj-idea-8khi follow-up): combines what was
    // previously a bold identity header ("New commit (sibling of X)") plus a separate plain
    // sub-label ("New commit description") into a single "Description for …" line - the two were
    // redundant, and splitting one piece of information across two lines added noise rather than
    // clarity. HTML panes, not JLabels, so the embedded change id renders with the same
    // bold-prefix/grey-remainder styling used everywhere else in the plugin (setStyledText/
    // TextCanvas.append(ChangeId)) instead of as plain text.
    internal val parentHeaderLabel = IconAwareHtmlPane(project).apply { alignmentX = JLabel.LEFT_ALIGNMENT }
    internal val childHeaderLabel = IconAwareHtmlPane(project).apply { alignmentX = JLabel.LEFT_ALIGNMENT }

    // Short labels for the two commits; match the merge picker and summary wording. Overwritten
    // immediately by updateDynamicLabels() in init{} - these are just well-formed placeholders.
    internal var firstCommitLabel: String = legendLabel("dialog.split.legend.stays")
    internal var secondCommitLabel: String = legendLabel("dialog.split.legend.new")

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
    // obvious: it stays on the original change ID in newParent mode, but moves to the new commit
    // otherwise - true of --parallel as well as the plain default, verified against real jj in
    // MutatingCommandsContractCliTest's "split --parallel on the working copy…" test (jj-idea-8khi).
    //
    // An HTML pane, not a JLabel (jj-idea-8khi): word-wraps within the column instead of forcing
    // it wider to fit the longest line, and lets the embedded change id use the same styling as
    // everywhere else (see setStyledText). Text is set once at construction - unlike modeNoteLabel
    // below, this note's mode (newParent or not) is fixed for the dialog's lifetime.
    private val workingCopyNoteLabel = IconAwareHtmlPane(project).apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        isVisible = sourceEntry.isWorkingCopy
        if (sourceEntry.isWorkingCopy) {
            setStyledText(if (newParent) "dialog.split.wc.stays" else "dialog.split.wc.moves", sourceEntry.id)
        }
    }

    // --- Mode note (jj-idea-8khi, GitHub #101 UX follow-up) ---
    // States the one fact the identity-first labels can't carry: where the new commit lands, and
    // (parallel only) that existing children become merges of both siblings. Always visible -
    // there's always a mode to explain - text is set live by updateDynamicLabels(). An HTML pane
    // for the same word-wrap/styling reasons as workingCopyNoteLabel above.
    private val modeNoteLabel = IconAwareHtmlPane(project).apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    // --- Test seam: injectable merge picker (avoids modal merge under tests) ---
    @org.jetbrains.annotations.TestOnly
    internal var hunkPickerForTest: ((FilePath) -> String?)? = null

    init {
        title = JujutsuBundle.message(if (newParent) "dialog.split.title.newParent" else "dialog.split.title")
        setOKButtonText(JujutsuBundle.message("dialog.split.button"))

        parallelCheckBox.addActionListener {
            updateDynamicLabels()
            updateSummary()
            previewController.currentFile?.let { previewController.refresh(it) }
        }
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
     * The preview shows the **split-off change that moves to the new commit**: the right
     * ("New commit") side is always its full content (it's the tip of the split, so it always
     * holds the full original content — see [splitPreviewPanes]). The left ("Stays") side
     * reflects what **remains on the original commit** — see [computePreviewLeftContent].
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

    /**
     * One identity-first vocabulary in every mode (jj-idea-8khi, GitHub #101 UX follow-up):
     * `parentHeaderLabel`/`parentDescriptionEditor` etc. keep their field names for the "stays"
     * side and `child*` for the "new commit" side (matching [SplitSpec]'s existing terms), but
     * the *text* they render no longer varies between "Parent"/"Child" and "First"/"Second" - see
     * class KDoc. Only the new-commit header's parenthetical (child/sibling/parent of …) and the
     * mode note's body vary with mode.
     */
    private fun updateDynamicLabels() {
        firstCommitLabel = legendLabel("dialog.split.legend.stays")
        secondCommitLabel = legendLabel("dialog.split.legend.new")

        parentHeaderLabel.setStyledText("dialog.split.stays.header", sourceEntry.id, bold = true)

        val newHeaderKey = when {
            newParent -> "dialog.split.new.header.parent"
            parallelCheckBox.isSelected -> "dialog.split.new.header.parallel"
            else -> "dialog.split.new.header.child"
        }
        childHeaderLabel.setStyledText(newHeaderKey, sourceEntry.id, bold = true)

        val noteKey = when {
            newParent -> "dialog.split.note.parent"
            parallelCheckBox.isSelected -> "dialog.split.note.parallel"
            else -> "dialog.split.note.child"
        }
        modeNoteLabel.setStyledText(noteKey, sourceEntry.id)
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
        modeNoteLabel.alignmentX = JLabel.LEFT_ALIGNMENT
        add(modeNoteLabel)
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

        val childBlock = descriptionBlock(childHeaderLabel, childDescriptionEditor)
        val parentBlock = descriptionBlock(parentHeaderLabel, parentDescriptionEditor)

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
            // New-commit description first: matches its position above the stays-here side in
            // the log when it's a child (default mode); an arbitrary but stable choice when it's
            // a sibling (--parallel), which has no "above" position of its own.
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

    private fun descriptionBlock(header: JComponent, editor: DescriptionEditor): JPanel {
        header.alignmentX = JLabel.LEFT_ALIGNMENT
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

    /**
     * True when any file has a hunk-picked parent-remainder override — see [applyPickedContent].
     * A genuine partial pick deliberately leaves the tick untouched (see [applyPickedContent]'s
     * KDoc), so this is the only signal that a hunks-only selection (nothing ticked, but a file
     * partially picked via "Pick Hunks…") actually has something to split off.
     *
     * Never true in [newParent] mode ("Pick Hunks…" is hidden there), so a hunks-only selection
     * can't rescue an otherwise-empty `-B` split.
     */
    private val isPartialSplit: Boolean get() = !newParent && firstCommitOverrides.isNotEmpty()

    override fun doValidate(): ValidationInfo? {
        val included = fileSelection.includedChanges // ticked = moving to the new commit
        val total = allChanges.size

        // Nothing ticked is only truly empty if there's also no partial hunk pick - a
        // hunks-only split (GitHub #117) leaves every tick untouched but has real content
        // to move via firstCommitOverrides. Mode-independent messages (jj-idea-8khi): ticking
        // always means "move to the new commit" and staying always means "stays here", in every
        // mode - see class KDoc.
        if (included.isEmpty() && !isPartialSplit) {
            return ValidationInfo(
                JujutsuBundle.message("dialog.split.validation.new.empty"),
                fileSelection.changesTree
            )
        }
        if (included.size == total && firstCommitOverrides.isEmpty()) {
            return ValidationInfo(
                JujutsuBundle.message("dialog.split.validation.stays.empty"),
                fileSelection.changesTree
            )
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

        val hunkSelection: HunkSelection? = if (isPartialSplit) {
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

    /** Current mode note text (for testing). */
    internal val modeNoteText: String get() = modeNoteLabel.text

    /** The mode note's own component, to check it doesn't force a wide preferred size (for testing). */
    internal val modeNoteComponent: JComponent get() = modeNoteLabel
}

/** Capitalize a legend bundle key value (e.g. "parent" → "Parent"). */
private fun legendLabel(key: String) =
    JujutsuBundle.message(key).replaceFirstChar { it.uppercaseChar() }

// The left column's approximate content budget: the dialog's own preferredSize is 960px (see
// createCenterPanel), split via OnePixelSplitter(false, 0.4f) into a ~384px left column, minus
// leftPanel's own left+right insets (8+4, see createCenterPanel). A plain JEditorPane/JLabel with
// no explicit width reports its *unwrapped single-line* width as "preferred" - without this fixed
// pixel width, the mode note's ~140-character sentence forces the whole left column wider to fit
// it on one line (confirmed empirically: an unconstrained IconAwareHtmlPane here still reported
// ~800px preferred width, not something HTML wrapping alone fixes).
private const val LABEL_WIDTH_PX = 340

/**
 * Render [messageKey] (a bundle message with a single `{0}` placeholder for a change id) as HTML,
 * substituting a fragment styled via [in.kkkev.jjidea.ui.components.append]'s bold-prefix/
 * grey-remainder rendering - the same styling a change id gets everywhere else in the plugin
 * (log table, commit details, other dialogs' "Source" panels) - instead of plain text
 * (jj-idea-8khi, GitHub #101 UX follow-up). [bold] wraps the whole rendered text in `<b>`, for the
 * header labels (which were plain bold-font `JLabel`s before this).
 *
 * Wrapped in a fixed-width `<div>` (see [LABEL_WIDTH_PX]) so the pane reports a bounded preferred
 * width and wraps instead of forcing its column wider - the same technique the platform's own
 * (deprecated) `ComponentPanelBuilder.createCommentComponent`/DSL `Row.comment` use internally.
 * Header text is short enough to always fit on one line at this width; the note text is what
 * actually needs to wrap.
 *
 * The bundle message itself is treated as raw HTML (not escaped), matching how those platform
 * comment helpers treat comment text - safe here since these particular messages are plain
 * English prose with no HTML metacharacters.
 */
private fun IconAwareHtmlPane.setStyledText(messageKey: String, id: ChangeId, bold: Boolean = false) {
    val idFragment = htmlText { append(id) }
    val message = JujutsuBundle.message(messageKey, idFragment)
    val body = if (bold) "<b>$message</b>" else message
    text = "<html><body><div style='width:${JBUI.scale(LABEL_WIDTH_PX)}px'>$body</div></body></html>"
}

/**
 * Describe the split state of [content] (relative to [baseContent]/[afterContent]) as a pair
 * of (left title, right title) label fragments, for the main file preview's diff titles —
 * e.g. an untouched (unticked) file reads "Stays (all changes)" / "New commit (no changes)"; a
 * fully-moved (ticked) file reads "Stays (no changes)" / "New commit (all changes)"; anything
 * else is "partial". Mode-agnostic (jj-idea-8khi, GitHub #101 UX follow-up): the labels passed in
 * already carry whatever mode-specific wording is needed, so this function no longer needs to
 * know the mode itself - it previously distinguished "(unchanged)" (parent/child) from
 * "(no changes)" (siblings, jj-idea-o6sw), a distinction the identity-first relabelling made
 * moot everywhere.
 */
internal fun describeSplitState(
    content: String,
    baseContent: String,
    afterContent: String,
    parentLabel: String,
    childLabel: String
): Pair<String, String> = when (content) {
    afterContent -> Pair(
        JujutsuBundle.message("dialog.split.hunks.allChanges", parentLabel),
        JujutsuBundle.message("dialog.split.hunks.noChanges", childLabel)
    )

    baseContent -> Pair(
        JujutsuBundle.message("dialog.split.hunks.noChanges", parentLabel),
        JujutsuBundle.message("dialog.split.hunks.allChanges", childLabel)
    )

    else -> Pair(
        JujutsuBundle.message("dialog.split.hunks.partial", parentLabel),
        JujutsuBundle.message("dialog.split.hunks.partial", childLabel)
    )
}

/**
 * The (left, right) [DiffPane]s for the main file preview: left is [content] itself (the
 * stays-side remainder — see [SplitDialog.computePreviewLeftContent]), right is always
 * [FileContents.after] (the new commit is the tip of the split, so it always holds the full
 * original content). Titles come from [describeSplitState], evaluated on the same [content] that
 * decides the left pane's text, so a pane's text and its own title can never disagree
 * (jj-idea-jb2q, GitHub #101).
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
