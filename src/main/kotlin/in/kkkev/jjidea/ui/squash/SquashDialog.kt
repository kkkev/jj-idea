package `in`.kkkev.jjidea.ui.squash

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.FilePath
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.ui.common.FileSelectionPanel
import `in`.kkkev.jjidea.ui.components.*
import `in`.kkkev.jjidea.ui.log.appendDecorations
import `in`.kkkev.jjidea.ui.log.appendStatusIndicators
import `in`.kkkev.jjidea.vcs.filePath
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Result of the squash dialog — the user's chosen parameters.
 */
data class SquashSpec(
    val revision: Revision,
    val filePaths: List<FilePath>,
    val description: Description,
    val keepEmptied: Boolean
)

/**
 * Merge descriptions from parent and source entries for the combined change.
 * Non-empty descriptions are joined with a blank line; empty ones are skipped.
 */
fun mergeDescriptions(parentDescription: String, sourceDescription: String): String = when {
    parentDescription.isNotEmpty() && sourceDescription.isNotEmpty() -> "$parentDescription\n\n$sourceDescription"
    parentDescription.isNotEmpty() -> parentDescription
    sourceDescription.isNotEmpty() -> sourceDescription
    else -> ""
}

/**
 * Dialog for configuring a `jj squash` operation.
 *
 * Shows source and destination entry info, file selection with checkboxes,
 * a description field, and a "keep emptied" option.
 */
class SquashDialog(
    private val project: Project,
    private val sourceEntry: LogEntry,
    private val parentEntry: LogEntry?,
    changes: List<com.intellij.openapi.vcs.changes.Change>,
    preSelectedFiles: Set<FilePath>? = null
) : DialogWrapper(project) {
    var result: SquashSpec? = null
        private set

    internal val fileSelection = FileSelectionPanel(project)
    private val mergedDescription = mergeDescriptions(
        parentEntry?.description?.actual ?: "",
        sourceEntry.description.actual
    )
    private val descriptionField = JBTextArea(mergedDescription, 4, 0)
    private val keepEmptiedCheckBox = JBCheckBox(JujutsuBundle.message("dialog.squash.keep.emptied"))

    /** Current description text, exposed for testing. */
    internal val descriptionText: String get() = descriptionField.text

    init {
        title = JujutsuBundle.message("dialog.squash.title")
        setOKButtonText(JujutsuBundle.message("dialog.squash.button"))
        if (preSelectedFiles != null) {
            val included = changes.filter { it.filePath in preSelectedFiles }
            fileSelection.setChanges(changes, included)
        } else {
            fileSelection.setChanges(changes)
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val topSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(createSectionLabel(JujutsuBundle.message("dialog.squash.source")))
            add(createEntryPane(sourceEntry))
            add(createSectionLabel(JujutsuBundle.message("dialog.squash.destination")))
            add(createDestinationPane())
        }

        val filesLabel = createSectionLabel(JujutsuBundle.message("dialog.squash.files"))

        val descriptionSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(createSectionLabel(JujutsuBundle.message("dialog.squash.description")))
            val scrollPane = JScrollPane(descriptionField).apply {
                alignmentX = JPanel.LEFT_ALIGNMENT
                preferredSize = Dimension(0, JBUI.scale(80))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(80))
            }
            add(scrollPane)
            add(keepEmptiedCheckBox.apply { alignmentX = JPanel.LEFT_ALIGNMENT })
        }

        val wrapper = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            val north = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(topSection)
                add(filesLabel)
            }
            add(north, BorderLayout.NORTH)
            add(fileSelection, BorderLayout.CENTER)
            add(descriptionSection, BorderLayout.SOUTH)
        }
        wrapper.preferredSize = Dimension(JBUI.scale(700), JBUI.scale(500))
        return wrapper
    }

    private fun createSectionLabel(text: String): JLabel {
        val label = JLabel(text)
        label.font = label.font.deriveFont(java.awt.Font.BOLD)
        label.alignmentX = JLabel.LEFT_ALIGNMENT
        label.border = JBUI.Borders.empty(4, 0)
        return label
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

    private fun createDestinationPane(): JComponent = if (parentEntry != null) {
        createEntryPane(parentEntry)
    } else {
        JLabel(JujutsuBundle.message("dialog.squash.destination.unknown")).apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (fileSelection.includedChanges.isEmpty()) {
            return ValidationInfo(JujutsuBundle.message("dialog.squash.no.files"), fileSelection)
        }
        return null
    }

    override fun doOKAction() {
        val filePaths = if (fileSelection.allIncluded) {
            emptyList()
        } else {
            fileSelection.includedChanges.mapNotNull { it.filePath }
        }

        val description = Description(descriptionField.text.trim())

        result = SquashSpec(
            revision = sourceEntry.id,
            filePaths = filePaths,
            description = description,
            keepEmptied = keepEmptiedCheckBox.isSelected
        )
        super.doOKAction()
    }
}
