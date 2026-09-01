package `in`.kkkev.jjidea.ui.rebase

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.common.createSectionLabel
import `in`.kkkev.jjidea.ui.common.createSourcePanel
import `in`.kkkev.jjidea.ui.common.createVerticalPanel
import `in`.kkkev.jjidea.ui.components.CommitPickerPanel
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

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
 * Left side: source section, source mode, destination picker (log-style table with search), placement mode.
 * Right side: simulated post-rebase preview graph.
 *
 * Known limitation: [CommitPickerPanel]'s whole-repo search (jj-idea-tq4b) can merge in a commit
 * whose ancestry isn't otherwise loaded, so [RebaseSimulator.excludedDestinationIds] — which
 * derives ancestry from the loaded set — may not catch every case where that commit is actually a
 * descendant of a source (an invalid destination). jj's own rebase command still rejects that
 * combination with a clear error; pre-loading the connecting range to catch it here would defeat
 * the point of the bounded log window.
 */
class RebaseDialog(
    private val project: Project,
    private val repo: JujutsuRepository,
    private val sourceEntries: List<LogEntry>
) : DialogWrapper(project) {
    var result: RebaseSpec? = null
        private set

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

    // Preview
    private val previewPanel = RebasePreviewPanel(project)

    // Destination picker — search + log-style table (jj-idea-tq4b)
    private val picker: CommitPickerPanel = CommitPickerPanel(
        project = project,
        repo = repo,
        searchPlaceholder = JujutsuBundle.message("dialog.rebase.destination.search"),
        multiSelect = true,
        onReloaded = {
            previewPanel.setEntries(picker.entries)
            updatePreviewPanel()
        }
    )

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

        // Update preview when destination selection or placement mode changes
        picker.table.selectionModel.addListSelectionListener { updatePreviewPanel() }
        destModeOnto.addActionListener { updatePreviewPanel() }
        destModeAfter.addActionListener { updatePreviewPanel() }
        destModeBefore.addActionListener { updatePreviewPanel() }

        // Re-filter destinations when source mode changes (excluded set depends on mode)
        sourceModeRevision.addActionListener { reloadDestinations() }
        sourceModeSource.addActionListener { reloadDestinations() }
        sourceModeBranch.addActionListener { reloadDestinations() }

        Disposer.register(disposable, picker)

        init()

        reloadDestinations()
    }

    override fun createCenterPanel(): JComponent {
        // Fixed-height top section: source info + source mode
        val topSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(createSectionLabel(JujutsuBundle.message("dialog.rebase.source")))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(createSourcePanel(project, sourceEntries))
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
            add(picker, BorderLayout.CENTER)
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

    private fun createSourceModePanel() = createVerticalPanel(sourceModeRevision, sourceModeSource, sourceModeBranch)

    private fun createPlacementModePanel() = createVerticalPanel(destModeOnto, destModeAfter, destModeBefore)

    private fun reloadDestinations() {
        val sourceIds = sourceEntries.map { it.id }.toSet()
        picker.reload { entry ->
            entry.id !in RebaseSimulator.excludedDestinationIds(picker.entries, sourceIds, selectedSourceMode)
        }
    }

    private fun updatePreviewPanel() {
        previewPanel.update(
            sourceEntries = sourceEntries,
            destinationIds = picker.selectedIds(),
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
        if (picker.selectedIds().isEmpty()) {
            return ValidationInfo(JujutsuBundle.message("dialog.rebase.destination.none"), picker.table)
        }
        return null
    }

    override fun doOKAction() {
        val destinations = picker.selectedEntries().map { it.id as Revision }

        result = RebaseSpec(
            revisions = sourceEntries.map { it.id },
            destinations = destinations,
            sourceMode = selectedSourceMode,
            destinationMode = selectedDestinationMode
        )
        super.doOKAction()
    }
}
