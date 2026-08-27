package `in`.kkkev.jjidea.ui.newchange

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.components.*
import `in`.kkkev.jjidea.ui.duplicate.validPlacementModes
import `in`.kkkev.jjidea.ui.log.*
import `in`.kkkev.jjidea.ui.rebase.RebasePreviewPanel
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.DocumentEvent

/**
 * Result of the "New Change…" dialog.
 */
data class NewChangeSpec(
    val description: Description,
    val parents: List<Revision>,
    val destinationMode: RebaseDestinationMode,
    val edit: Boolean
)

/**
 * A placeholder id for the not-yet-created change being previewed. It only needs to be distinct
 * from any real id the simulator might see (guaranteed here simply by length - real change/commit
 * ids are always 32/40 hex-like characters) - unlike a real entry's id, it's never linkified or
 * otherwise treated as navigable, since [LogEntry.pending] makes the renderer skip that path
 * entirely (see [in.kkkev.jjidea.ui.components.appendPendingSummary]). Never shown directly
 * either way: the preview always passes an explicit `sourceLabel` (see
 * [RebasePreviewPanel.update]) instead of this id's own text.
 */
private val PENDING_CHANGE_ID = ChangeId("new", "new")
private val PENDING_COMMIT_ID = CommitId("new")

/**
 * Dialog for `jj new`, unifying the plain "new change with a description" flow with insertion
 * (`jj new -A`/`jj new -B`) behind one surface (jj-idea-grc8) — see
 * [in.kkkev.jjidea.actions.change.newChangeFromAction].
 *
 * [targetEntries] are fixed (the log selection that invoked the action, exactly as
 * `jj new <targets>` would take them positionally); the dialog only lets the user choose a
 * description, placement relative to the targets, and whether the working copy follows.
 *
 * The preview reuses [in.kkkev.jjidea.ui.rebase.RebaseSimulator] by treating the change about to
 * be created as a synthetic, parentless [LogEntry] (id [PENDING_CHANGE_ID]) and [targetEntries]
 * as the simulated rebase's destinations - `reparentOnto`/`reparentInsertAfter`/
 * `reparentInsertBefore` then produce exactly the post-`jj new` graph for all three placement
 * modes with no simulator changes. The synthetic entry must be included in the entries handed to
 * [RebasePreviewPanel.setEntries] (not just as the "source"), since the simulator only reparents
 * entries already present in that list.
 */
class NewChangeDialog(
    private val project: Project,
    private val repo: JujutsuRepository,
    private val targetEntries: List<LogEntry>
) : DialogWrapper(project) {
    var result: NewChangeSpec? = null
        private set

    private var repoEntries: List<LogEntry> = emptyList()

    private val descriptionField = JBTextArea().apply {
        rows = 4
        lineWrap = true
        wrapStyleWord = true
        toolTipText = JujutsuBundle.message("dialog.newchange.description.tooltip")
    }

    private val destModeOnto = JRadioButton(JujutsuBundle.message("dialog.newchange.placement.onto")).apply {
        toolTipText = JujutsuBundle.message("dialog.newchange.placement.onto.description")
        isSelected = true
    }
    private val destModeAfter = JRadioButton(JujutsuBundle.message("dialog.newchange.placement.after")).apply {
        toolTipText = JujutsuBundle.message("dialog.newchange.placement.after.description")
    }
    private val destModeBefore = JRadioButton(JujutsuBundle.message("dialog.newchange.placement.before")).apply {
        toolTipText = JujutsuBundle.message("dialog.newchange.placement.before.description")
    }

    private val editCheckBox = JCheckBox(JujutsuBundle.message("dialog.newchange.edit")).apply {
        isSelected = true
        toolTipText = JujutsuBundle.message("dialog.newchange.edit.description")
    }

    private val previewPanel = RebasePreviewPanel()

    init {
        title = JujutsuBundle.message("dialog.newchange.title")
        setOKButtonText(JujutsuBundle.message("dialog.newchange.button"))

        ButtonGroup().apply {
            add(destModeOnto)
            add(destModeAfter)
            add(destModeBefore)
        }

        destModeOnto.addActionListener { updatePreviewPanel() }
        destModeAfter.addActionListener { updatePreviewPanel() }
        destModeBefore.addActionListener { updatePreviewPanel() }
        editCheckBox.addActionListener { updatePreviewPanel() }
        descriptionField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updatePreviewPanel()
        })

        init()

        runInBackground(ModalityState.any()) {
            val entries = repo.logCache.all
            runLater {
                if (!isDisposed) {
                    repoEntries = entries
                    updateModeAvailability()
                    updatePreviewPanel()
                }
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val leftWrapper = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(createSectionLabel(JujutsuBundle.message("dialog.newchange.target")))
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                    add(createTargetPanel())
                    add(Box.createVerticalStrut(JBUI.scale(12)))
                    add(createSectionLabel(JujutsuBundle.message("dialog.newchange.description.label")))
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                    add(createDescriptionPanel())
                    add(Box.createVerticalStrut(JBUI.scale(12)))
                    add(createSectionLabel(JujutsuBundle.message("dialog.newchange.placement")))
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                    add(createPlacementModePanel())
                    add(Box.createVerticalStrut(JBUI.scale(12)))
                    add(editCheckBox.apply { alignmentX = JPanel.LEFT_ALIGNMENT })
                },
                BorderLayout.NORTH
            )
        }

        val rightPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(createSectionLabel(JujutsuBundle.message("dialog.newchange.preview")), BorderLayout.NORTH)
            add(previewPanel, BorderLayout.CENTER)
        }

        val splitter = OnePixelSplitter(false, 0.4f).apply {
            firstComponent = leftWrapper
            secondComponent = rightPanel
        }

        return JPanel(BorderLayout()).apply {
            add(splitter, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(900), JBUI.scale(500))
        }
    }

    private fun createSectionLabel(text: String): JLabel {
        val label = JLabel(text)
        label.font = label.font.deriveFont(java.awt.Font.BOLD)
        label.alignmentX = JLabel.LEFT_ALIGNMENT
        return label
    }

    /** Multi-line description, matching [in.kkkev.jjidea.ui.workingcopy.WorkingCopyControlsPanel]'s styling. */
    private fun createDescriptionPanel(): JComponent =
        ScrollPaneFactory.createScrollPane(descriptionField).apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
            minimumSize = JBUI.size(200, 70)
            preferredSize = JBUI.size(400, 90)
        }

    private fun createTargetPanel() = IconAwareHtmlPane(project).apply {
        alignmentX = JPanel.LEFT_ALIGNMENT
        text = htmlString {
            append(targetEntries, separator = "\n") { entry ->
                appendStatusIndicators(entry)
                append(entry.id)
                append(" ")
                appendDescriptionAndEmptyIndicator(entry)
                append(" ")
                appendDecorations(entry)
            }
        }
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

    /**
     * Disable placement modes the fixed [targetEntries] can't support (see
     * [in.kkkev.jjidea.ui.duplicate.DuplicateImmutabilityGuard] - the same guard applies here:
     * `jj new -B X` rewrites X directly, `jj new -A X` rewrites X's children, exactly like
     * `jj duplicate`'s insert modes), falling back to Onto - always valid - if the mode
     * currently selected just became unavailable.
     */
    private fun updateModeAvailability() {
        val targetIds = targetEntries.map { it.id }.toSet()
        val valid = validPlacementModes(repoEntries, targetIds)
        destModeAfter.isEnabled = RebaseDestinationMode.INSERT_AFTER in valid
        destModeBefore.isEnabled = RebaseDestinationMode.INSERT_BEFORE in valid

        if (selectedDestinationMode !in valid) {
            destModeOnto.isSelected = true
            updatePreviewPanel()
        }
    }

    private fun syntheticEntry(isWorkingCopy: Boolean) = LogEntry(
        repo = repo,
        id = PENDING_CHANGE_ID,
        commitId = PENDING_COMMIT_ID,
        underlyingDescription = descriptionField.text,
        isEmpty = true,
        pending = true,
        isWorkingCopy = isWorkingCopy
    )

    /**
     * Mirrors `jj new`'s actual effect on `@`: when [editCheckBox] is checked, the working copy
     * moves to the new change, so the preview's bold "@" marker (driven by [LogEntry.isWorkingCopy]
     * via `entryCanvas`) moves too - the real current working copy loses it and the synthetic entry
     * gains it. Unchecked (`--no-edit`), the real working copy keeps it and the synthetic entry is
     * just another change.
     */
    private fun updatePreviewPanel() {
        val edit = editCheckBox.isSelected
        val synthetic = syntheticEntry(isWorkingCopy = edit)
        val entries = if (edit) {
            repoEntries.map { entry -> if (entry.isWorkingCopy) entry.copy(isWorkingCopy = false) else entry }
        } else {
            repoEntries
        }

        previewPanel.setEntries(entries + synthetic)
        previewPanel.update(
            sourceEntries = listOf(synthetic),
            destinationIds = targetEntries.map { it.id }.toSet(),
            sourceMode = RebaseSourceMode.REVISION,
            destinationMode = selectedDestinationMode,
            sourceLabel = JujutsuBundle.message("dialog.newchange.preview.sourceLabel")
        )
    }

    private val selectedDestinationMode: RebaseDestinationMode
        get() = when {
            destModeOnto.isSelected -> RebaseDestinationMode.ONTO
            destModeAfter.isSelected -> RebaseDestinationMode.INSERT_AFTER
            else -> RebaseDestinationMode.INSERT_BEFORE
        }

    override fun doOKAction() {
        result = NewChangeSpec(
            description = Description(descriptionField.text),
            parents = targetEntries.map { it.id },
            destinationMode = selectedDestinationMode,
            edit = editCheckBox.isSelected
        )
        super.doOKAction()
    }
}
