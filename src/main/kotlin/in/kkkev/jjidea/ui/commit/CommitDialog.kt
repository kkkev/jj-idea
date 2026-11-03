package `in`.kkkev.jjidea.ui.commit

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.diffedit.HunkPicker
import `in`.kkkev.jjidea.diffedit.HunkPickerLabels
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
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
 * Result of the commit dialog.
 *
 * [filePaths] is the ticked pane's files (`jj commit`'s "selected" fileset argument): they stay on
 * the working copy's change ID, and everything else moves to a new working-copy change on top.
 *
 * [description] is passed as `-m` and describes the side [filePaths] ends up in.
 *
 * [hunkSelection], when non-null, is the content the diff editor writes into the **lower** commit
 * (the committed change); files it doesn't cover fall back to the [filePaths] fileset.
 */
data class CommitSpec(
    /** Whole-file fast path: see this class's KDoc. */
    val filePaths: List<FilePath>,
    /** Hunk-level selection. Non-null when at least one file is partially selected. */
    val hunkSelection: HunkSelection?,
    val description: Description
)

/**
 * Dialog for configuring a `jj commit` operation (GitHub #157). Derived from the "Split into New
 * Parent" mode of [in.kkkev.jjidea.ui.split.SplitDialog], which has the same tick polarity.
 *
 * Layout: left panel = changed-files list with file-level checkboxes + summary; right panel =
 * native read-only diff preview for the selected file + "Pick Hunks…" button. The description
 * field is at the bottom.
 *
 * Ticking a file **commits** it: it stays on [sourceEntry]'s own change ID, which gets the
 * description. Unticked files move to a **new working-copy change** on top. Nothing is ticked by
 * default. "Pick Hunks…" opens IDEA's merge window to commit a subset of a file's hunks.
 *
 * Hunk content is modelled by the **lower** (older) of the two resulting commits, because that is
 * what `jj commit --tool` reads back from `$right`: the committed change (verified in
 * `CommitContractCliTest`). A file's lower content is its pre-change (`base`) content exactly
 * when unticked, and its full (`after`) content otherwise - see [computePreviewLeftContent].
 * [firstCommitOverrides] holds a picked lower-commit content.
 */
class CommitDialog(
    private val project: Project,
    private val sourceEntry: LogEntry,
    changes: List<Change>,
    preSelectedFiles: Set<FilePath>? = null
) : DialogWrapper(project) {
    var result: CommitSpec? = null
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
        isIncluded = { fp -> fileSelection.includedChanges.any { it.filePath == fp } },
        movesToLabel = { secondCommitLabel },
        // Lower commit is the committed change itself (hunks are committed when applied).
        invertApplied = false,
        onHunksChanged = { fp, content, contents ->
            applyPickedContent(fp, content, contents.before, contents.after)
            updateSummary()
        }
    )
    private val diffPreview get() = previewController.preview

    // --- "Pick Hunks…" button ---
    internal val pickHunksButton = previewController.pickHunksButton.apply {
        addActionListener { onPickHunks() }
    }

    // --- Description ---
    internal val descriptionEditor = DescriptionEditor(project).apply {
        text = sourceEntry.description
        Disposer.register(disposable, this)
    }

    // --- Labels ---
    // An HTML pane, not a JLabel, so the embedded change id renders with the same
    // bold-prefix/grey-remainder styling used everywhere else in the plugin (setStyledText/
    // TextCanvas.append(ChangeId)) instead of as plain text.
    internal val headerLabel = IconAwareHtmlPane(project).apply { alignmentX = JLabel.LEFT_ALIGNMENT }

    // Short labels for the two commits; match the merge picker and summary wording. The first
    // commit is the unticked side, the second the ticked (committed) one.
    internal val firstCommitLabel: String = legendLabel("dialog.commit.legend.remaining")
    internal val secondCommitLabel: String = legendLabel("dialog.commit.legend.committed")

    // --- Summary ---
    internal val summaryLabel = JBLabel().apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    // --- Note ---
    // States where the ticked and unticked changes end up. An HTML pane, not a JLabel: word-wraps
    // within the column instead of forcing it wider to fit the longest line, and lets the embedded
    // change id use the same styling as everywhere else (see setStyledText).
    private val noteLabel = IconAwareHtmlPane(project).apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        setStyledText("dialog.commit.note", sourceEntry.id)
    }

    // --- Test seam: injectable merge picker (avoids modal merge under tests) ---
    @org.jetbrains.annotations.TestOnly
    internal var hunkPickerForTest: ((FilePath) -> String?)? = null

    init {
        title = JujutsuBundle.message("dialog.commit.title")
        setOKButtonText(JujutsuBundle.message("dialog.commit.button"))

        headerLabel.setStyledText("dialog.commit.header", sourceEntry.id, bold = true)

        // Populate file selection panel.
        // Checked/included files are the ones being COMMITTED; everything left unticked moves to
        // the new working copy. Nothing is ticked by default — the user opts in to what gets
        // committed. preSelectedFiles (e.g. right-clicked files via "Commit...") start ticked,
        // since that's what the user asked to commit.
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
        // The tree applies the initial ticks asynchronously, so count them again once it has.
        fileSelection.changesTree.invokeAfterRefresh { updateSummary() }
        init()
    }

    // ---- File inclusion sync ----

    private fun onFileInclusionChanged() {
        val nowIncluded = fileSelection.includedChanges.map { it.filePath }.toSet()

        // Files newly unticked → clear any partial override (file moves fully to the new working copy).
        for (fp in (previousIncluded - nowIncluded)) {
            firstCommitOverrides.remove(fp)
        }
        // Files newly ticked → clear any partial override (file is fully committed).
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
     * Load the working copy's before/after content and file type for [change], off the EDT —
     * the [HunkPickPreviewController] loader.
     *
     * The preview shows the file's own change, before → after, with a gutter checkbox per hunk
     * ticking the hunks that are committed; the ticks are derived from the content that
     * **lands in the lower commit** — see [computePreviewLeftContent].
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
     * Compute the left-side (lower-commit) content for the diff preview: an explicit override
     * wins, otherwise it's derived from the tick. The lower commit is the committed change, so a
     * ticked file gives it everything and an unticked one leaves it with the pre-change content.
     * Extracted for test seaming; takes plain strings so [FileContents] is not exposed.
     */
    internal fun computePreviewLeftContent(
        isTicked: Boolean,
        override: String?,
        baseContent: String,
        afterContent: String
    ): String = when {
        override != null -> override
        !isTicked -> baseContent
        else -> afterContent
    }

    // ---- Hunk picker ----

    private fun onPickHunks() {
        val fp = previewController.currentFile ?: return
        val data = previewController.cachedContents(fp) ?: return
        val isTicked = fileSelection.includedChanges.any { it.filePath == fp }

        // Resume any existing partial pick; otherwise start from the tick-derived default.
        val initialContent = firstCommitOverrides[fp]
            ?: computePreviewLeftContent(isTicked, null, data.before, data.after)

        val pickedContent: String? = hunkPickerForTest?.invoke(fp)
            ?: HunkPicker.pickRemainderContent(
                project = project,
                fileName = fp.name,
                fileType = data.fileType,
                baseContent = data.before,
                afterContent = data.after,
                initialContent = initialContent,
                labels = HunkPickerLabels.forSplitNewParent(firstCommitLabel, secondCommitLabel)
            )

        if (pickedContent == null) return // user cancelled — keep prior state

        applyPickedContent(fp, pickedContent, data.before, data.after)
        previewController.refresh(fp)
        updateSummary()
    }

    /**
     * Apply a hunk-picker result for [fp]. Fully-none/fully-all results are genuinely resolved
     * states and adjust the tick accordingly (see [computePreviewLeftContent]); anything else is
     * a genuine partial, which stores
     * the parent-remainder override but **deliberately leaves the tick state untouched**.
     *
     * The tick is inert once an override exists — every downstream read of a file's content
     * (`doOKAction`, the preview) checks the override first. Force-ticking a partial file here
     * would make a half-picked file look fully committed, which wouldn't be true;
     * the half-checked render (`partialChanges`, synced by the caller's `updateSummary()`) is
     * what should communicate "partial" to the user, not the tick.
     */
    internal fun applyPickedContent(fp: FilePath, pickedContent: String, baseContent: String, afterContent: String) {
        when (pickedContent) {
            baseContent -> {
                // Lower commit gets nothing: nothing is committed (untick).
                firstCommitOverrides.remove(fp)
                ensureFileExcluded(fp)
            }

            afterContent -> {
                // Lower commit gets everything: the mirror image of the case above.
                firstCommitOverrides.remove(fp)
                ensureFileIncluded(fp)
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

    private fun updateSummary() {
        val childFiles = fileSelection.includedChanges.size // ticked = committed
        val totalFiles = allChanges.size
        val parentFiles = totalFiles - childFiles
        val partialCount = firstCommitOverrides.size

        // Partial files contribute hunks to both commits, so they appear in both counts.
        // Committed side first.
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
        add(createSectionLabel(JujutsuBundle.message("dialog.commit.source")))
        add(createEntryPane(sourceEntry))
        noteLabel.alignmentX = JLabel.LEFT_ALIGNMENT
        add(noteLabel)
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

        panel.add(descriptionBlock(headerLabel, descriptionEditor))

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
     */
    private val isPartialSplit: Boolean get() = firstCommitOverrides.isNotEmpty()

    override fun doValidate(): ValidationInfo? {
        val included = fileSelection.includedChanges // ticked = committed

        // Nothing ticked is only truly empty if there's also no partial hunk pick - a
        // hunks-only commit (GitHub #117) leaves every tick untouched but has real content
        // to commit via firstCommitOverrides. Everything ticked is fine: it commits everything.
        if (included.isEmpty() && !isPartialSplit) {
            return ValidationInfo(
                JujutsuBundle.message("dialog.commit.validation.empty"),
                fileSelection.changesTree
            )
        }
        return null
    }

    // ---- OK action ----

    override fun doOKAction() {
        val ticked = fileSelection.includedChanges.toList()
        val tickedPaths = ticked.map { it.filePath }.toSet()

        // The ticked pane's files become the fileset passed to `jj commit` (its "selected"
        // argument) - see CommitSpec's KDoc.
        val selectedPaths = ticked.map { it.filePath }

        val hunkSelection: HunkSelection? = if (isPartialSplit) {
            // Build the first commit's content for every changed file.
            // buildHunkSelection's "included" means "written into the staging tree", i.e. the change
            // lands in the LOWER commit: the ticked files (the committed change). Deletions of such files go
            // through the deletion manifest (an empty `after` would otherwise be written as an
            // empty file, jj-idea-5g8h).
            buildHunkSelection(
                changes = allChanges,
                root = sourceEntry.repo.directory,
                overrides = firstCommitOverrides,
                isIncluded = { it in tickedPaths },
                isDeletion = { it.afterRevision == null },
                contentFor = { change, landsInFirstCommit ->
                    if (landsInFirstCommit) previewController.cachedContents(change.filePath)?.after else null
                }
            )
        } else {
            null
        }

        result = CommitSpec(
            filePaths = selectedPaths,
            hunkSelection = hunkSelection,
            description = Description(descriptionEditor.text.actual.trim())
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

    /** Current description text (for testing). */
    internal val descriptionText: String get() = descriptionEditor.text.actual

    /** Current note text (for testing). */
    internal val noteText: String get() = noteLabel.text

    /** The note's own component, to check it doesn't force a wide preferred size (for testing). */
    internal val noteComponent: JComponent get() = noteLabel
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
