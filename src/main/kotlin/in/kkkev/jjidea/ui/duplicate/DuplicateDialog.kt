package `in`.kkkev.jjidea.ui.duplicate

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.common.createSectionLabel
import `in`.kkkev.jjidea.ui.common.createSourcePanel
import `in`.kkkev.jjidea.ui.components.CommitPickerPanel
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * Result of the duplicate dialog — the user's chosen destination and placement.
 */
data class DuplicateSpec(
    val destinations: List<Revision>,
    val destinationMode: RebaseDestinationMode
)

/**
 * Dialog for configuring the destination of a `jj duplicate` operation.
 *
 * Unlike [in.kkkev.jjidea.ui.rebase.RebaseDialog], there is no source-mode concept
 * (duplicate's revisions are always positional) and no preview panel; this dialog only
 * picks a destination and placement (onto/after/before).
 */
class DuplicateDialog(
    private val project: Project,
    private val repo: JujutsuRepository,
    private val sourceEntries: List<LogEntry>
) : DialogWrapper(project) {
    var result: DuplicateSpec? = null
        private set

    /**
     * True while [reloadDestinations] (and the placement-mode reset it can trigger) is in
     * progress, so the destination-table's raw selection listener doesn't fire redundantly once
     * per row while [CommitPickerPanel] restores the previous selection internally.
     */
    private var updating = false

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

    // Destination picker — search + log-style table (jj-idea-tq4b)
    private val picker: CommitPickerPanel = CommitPickerPanel(
        project = project,
        repo = repo,
        searchPlaceholder = JujutsuBundle.message("dialog.duplicate.destination.search"),
        multiSelect = true,
        onReloaded = { updateModeAvailability() }
    )

    init {
        title = JujutsuBundle.message("dialog.duplicate.title")
        setOKButtonText(JujutsuBundle.message("dialog.duplicate.button"))

        ButtonGroup().apply {
            add(destModeOnto)
            add(destModeAfter)
            add(destModeBefore)
        }

        // Re-filter destinations when placement mode changes (invalid set depends on mode)
        val onModeChanged = { if (!updating) reloadDestinations() }
        destModeOnto.addActionListener { onModeChanged() }
        destModeAfter.addActionListener { onModeChanged() }
        destModeBefore.addActionListener { onModeChanged() }

        // Disable placement modes that the current destination selection can't support
        picker.table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !updating) updateModeAvailability()
        }

        Disposer.register(disposable, picker)

        init()

        reloadDestinations()
    }

    override fun createCenterPanel(): JComponent {
        val topSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(createSectionLabel(JujutsuBundle.message("dialog.duplicate.source")))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(createSourcePanel(project, sourceEntries))
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(JSeparator().apply { alignmentX = JPanel.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(createSectionLabel(JujutsuBundle.message("dialog.duplicate.destination")))
            add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        val bottomSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(createSectionLabel(JujutsuBundle.message("dialog.duplicate.placement")))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(createPlacementModePanel())
        }

        val wrapper = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(topSection, BorderLayout.NORTH)
            add(picker, BorderLayout.CENTER)
            add(bottomSection, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(550), JBUI.scale(500))
        }
        return wrapper
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

    private fun reloadDestinations() {
        val sourceIds = sourceEntries.map { it.id }.toSet()
        updating = true
        try {
            picker.reload { entry ->
                // Destinations that would make jj rewrite an immutable commit under the current
                // placement mode (see DuplicateImmutabilityGuard) — never offered as a target.
                // Unlike rebase, duplicating onto a descendant (or even a source itself, once
                // it's a copy) is meaningful, so beyond the immutability guard, the only
                // revisions excluded from the picker are the sources themselves.
                val invalid = invalidDestinationIds(picker.entries, selectedDestinationMode)
                entry.id !in sourceIds && entry.id !in invalid
            }
        } finally {
            updating = false
        }
    }

    /**
     * Disable placement modes the current destination selection can't support (see
     * [validPlacementModes]), falling back to Onto — always valid — if the mode that's
     * currently selected just became unavailable.
     */
    private fun updateModeAvailability() {
        val valid = validPlacementModes(picker.entries, picker.selectedIds())
        destModeAfter.isEnabled = RebaseDestinationMode.INSERT_AFTER in valid
        destModeBefore.isEnabled = RebaseDestinationMode.INSERT_BEFORE in valid

        if (selectedDestinationMode !in valid) {
            updating = true
            try {
                destModeOnto.isSelected = true
            } finally {
                updating = false
            }
            reloadDestinations()
        }
    }

    private val selectedDestinationMode: RebaseDestinationMode
        get() = when {
            destModeOnto.isSelected -> RebaseDestinationMode.ONTO
            destModeAfter.isSelected -> RebaseDestinationMode.INSERT_AFTER
            else -> RebaseDestinationMode.INSERT_BEFORE
        }

    override fun doValidate(): ValidationInfo? {
        if (picker.selectedIds().isEmpty()) {
            return ValidationInfo(JujutsuBundle.message("dialog.duplicate.destination.none"), picker.table)
        }
        return null
    }

    override fun doOKAction() {
        val destinations = picker.selectedEntries().map { it.id as Revision }

        result = DuplicateSpec(
            destinations = destinations,
            destinationMode = selectedDestinationMode
        )
        super.doOKAction()
    }
}
