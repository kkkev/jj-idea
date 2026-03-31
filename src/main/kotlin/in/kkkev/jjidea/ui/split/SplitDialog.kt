package `in`.kkkev.jjidea.ui.split

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.ui.common.JujutsuChangesTree
import `in`.kkkev.jjidea.ui.components.*
import `in`.kkkev.jjidea.ui.log.appendDecorations
import `in`.kkkev.jjidea.ui.log.appendStatusIndicators
import `in`.kkkev.jjidea.vcs.filePath
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Result of the split dialog — the user's chosen parameters.
 *
 * [filePaths] are the files for the **parent** commit (files passed to `jj split`).
 * [childDescription] is the description for the child commit (set via `jj describe` after split).
 */
data class SplitSpec(
    val revision: Revision,
    val filePaths: List<FilePath>,
    val description: Description,
    val childDescription: Description?,
    val parallel: Boolean
)

/**
 * Dialog for configuring a `jj split` operation.
 *
 * Dual-tree layout: child tree (top) and parent tree (bottom), with transfer buttons between them.
 * Right side shows a live preview graph. Both parent and child get description fields.
 *
 * In linear mode: labels say "Child (new commit)" / "Parent (stays at original position)".
 * In parallel mode: labels say "First" / "Second".
 */
class SplitDialog(
    private val project: Project,
    private val sourceEntry: LogEntry,
    changes: List<Change>,
    allEntries: List<LogEntry> = emptyList(),
    preSelectedFiles: Set<FilePath>? = null
) : DialogWrapper(project) {
    var result: SplitSpec? = null
        private set

    private val allChanges = changes.toList()

    // Child tree (top) — starts empty (or with pre-selected files)
    internal val childTree = JujutsuChangesTree(project)
    private var childChanges: MutableList<Change> = if (preSelectedFiles != null) {
        allChanges.filter { it.filePath in preSelectedFiles }.toMutableList()
    } else {
        mutableListOf()
    }

    // Parent tree (bottom) — starts with all files (minus pre-selected)
    internal val parentTree = JujutsuChangesTree(project)
    private var parentChanges: MutableList<Change> = if (preSelectedFiles != null) {
        allChanges.filter { it.filePath !in preSelectedFiles }.toMutableList()
    } else {
        allChanges.toMutableList()
    }

    // Descriptions
    internal val parentDescriptionField = JBTextArea(sourceEntry.description.actual, 2, 0)
    internal val childDescriptionField = JBTextArea(sourceEntry.description.actual, 2, 0)

    // Dynamic labels
    internal val childHeaderLabel = JLabel()
    internal val parentHeaderLabel = JLabel()
    private val childDescriptionLabel = JLabel()
    private val parentDescriptionLabel = JLabel()
    internal val childEmptyLabel = JBLabel(JujutsuBundle.message("dialog.split.empty.tree")).apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        border = JBUI.Borders.empty(8)
    }

    // Options
    internal val parallelCheckBox = JBCheckBox(JujutsuBundle.message("dialog.split.parallel"))

    // Transfer actions (stored to update text dynamically)
    private lateinit var moveToChildAction: DumbAwareAction
    private lateinit var moveToParentAction: DumbAwareAction

    // Preview
    private val previewPanel = SplitPreviewPanel()
    private val repoEntries = allEntries.filter { it.repo == sourceEntry.repo }

    /** Current parent description text, exposed for testing. */
    internal val parentDescriptionText: String get() = parentDescriptionField.text

    /** Current child description text, exposed for testing. */
    internal val childDescriptionText: String get() = childDescriptionField.text

    private val descriptionChangeListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) = updatePreview()
        override fun removeUpdate(e: DocumentEvent?) = updatePreview()
        override fun changedUpdate(e: DocumentEvent?) = updatePreview()
    }

    init {
        title = JujutsuBundle.message("dialog.split.title")
        setOKButtonText(JujutsuBundle.message("dialog.split.button"))

        parallelCheckBox.addActionListener {
            updateDynamicLabels()
            updatePreview()
        }

        parentDescriptionField.document.addDocumentListener(descriptionChangeListener)
        childDescriptionField.document.addDocumentListener(descriptionChangeListener)

        updateDynamicLabels()
        refreshTrees()

        previewPanel.setEntries(repoEntries)
        updatePreview()

        init()
    }

    private fun updateDynamicLabels() {
        val parallel = parallelCheckBox.isSelected
        childHeaderLabel.text = JujutsuBundle.message(
            if (parallel) "dialog.split.child.header.parallel" else "dialog.split.child.header"
        )
        childHeaderLabel.font = childHeaderLabel.font.deriveFont(Font.BOLD)

        parentHeaderLabel.text = JujutsuBundle.message(
            if (parallel) "dialog.split.parent.header.parallel" else "dialog.split.parent.header"
        )
        parentHeaderLabel.font = parentHeaderLabel.font.deriveFont(Font.BOLD)

        childDescriptionLabel.text = JujutsuBundle.message(
            if (parallel) "dialog.split.child.description.parallel" else "dialog.split.child.description"
        )
        parentDescriptionLabel.text = JujutsuBundle.message(
            if (parallel) "dialog.split.parent.description.parallel" else "dialog.split.parent.description"
        )

        childEmptyLabel.text = JujutsuBundle.message(
            if (parallel) "dialog.split.empty.tree.parallel" else "dialog.split.empty.tree"
        )

        if (::moveToChildAction.isInitialized) {
            val childKey = if (parallel) "dialog.split.move.to.child.parallel" else "dialog.split.move.to.child"
            moveToChildAction.templatePresentation.text = JujutsuBundle.message(childKey)
            val parentKey =
                if (parallel) "dialog.split.move.to.parent.parallel" else "dialog.split.move.to.parent"
            moveToParentAction.templatePresentation.text = JujutsuBundle.message(parentKey)
        }
    }

    private fun refreshTrees() {
        childTree.setChangesToDisplay(childChanges)
        parentTree.setChangesToDisplay(parentChanges)
        childEmptyLabel.isVisible = childChanges.isEmpty()
    }

    private fun moveToChild() {
        val selected = parentTree.selectedChanges.toList()
        if (selected.isEmpty()) return

        parentChanges.removeAll(selected.toSet())
        childChanges.addAll(selected)
        refreshTrees()
        updatePreview()
    }

    private fun moveToParent() {
        val selected = childTree.selectedChanges.toList()
        if (selected.isEmpty()) return

        childChanges.removeAll(selected.toSet())
        parentChanges.addAll(selected)
        refreshTrees()
        updatePreview()
    }

    private fun updatePreview() {
        if (repoEntries.isEmpty()) return
        val parallel = parallelCheckBox.isSelected
        val parentKey = if (parallel) "dialog.split.legend.first" else "dialog.split.legend.parent"
        val childKey = if (parallel) "dialog.split.legend.second" else "dialog.split.legend.child"
        val parentLabel = JujutsuBundle.message(parentKey)
        val childLabel = JujutsuBundle.message(childKey)
        previewPanel.update(
            sourceEntry,
            parallel,
            parentLabel,
            childLabel,
            parentDescription = parentDescriptionField.text.trim(),
            childDescription = childDescriptionField.text.trim()
        )
    }

    override fun createCenterPanel(): JComponent {
        val leftPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(createSourceSection(), BorderLayout.NORTH)
            add(createTreesSection(), BorderLayout.CENTER)
            add(createBottomSection(), BorderLayout.SOUTH)
        }

        val rightPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(createSectionLabel(JujutsuBundle.message("dialog.split.preview")), BorderLayout.NORTH)
            add(previewPanel, BorderLayout.CENTER)
        }

        val splitter = OnePixelSplitter(false, 0.75f).apply {
            firstComponent = leftPanel
            secondComponent = rightPanel
        }

        val wrapper = JPanel(BorderLayout())
        wrapper.add(splitter, BorderLayout.CENTER)
        wrapper.preferredSize = Dimension(JBUI.scale(900), JBUI.scale(600))
        return wrapper
    }

    private fun createSourceSection() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(createSectionLabel(JujutsuBundle.message("dialog.split.source")))
        add(createEntryPane(sourceEntry))
        add(Box.createVerticalStrut(JBUI.scale(8)))
    }

    private fun createTreesSection(): JComponent {
        // Child section (top)
        val childSection = JPanel(BorderLayout()).apply {
            add(createTreeHeader(childHeaderLabel, childDescriptionLabel, childDescriptionField), BorderLayout.NORTH)
            add(createTreeWithToolbar(childTree, childEmptyLabel), BorderLayout.CENTER)
        }

        // Transfer buttons
        val transferBar = createTransferBar()

        // Parent section (bottom)
        val parentSection = JPanel(BorderLayout()).apply {
            add(createTreeHeader(parentHeaderLabel, parentDescriptionLabel, parentDescriptionField), BorderLayout.NORTH)
            add(createTreeWithToolbar(parentTree, null), BorderLayout.CENTER)
        }

        // Stack: child, transfer buttons, parent
        val treesPanel = JPanel(BorderLayout())
        val topHalf = JPanel(BorderLayout()).apply {
            add(childSection, BorderLayout.CENTER)
            add(transferBar, BorderLayout.SOUTH)
        }

        val verticalSplitter = OnePixelSplitter(true, 0.5f).apply {
            firstComponent = topHalf
            secondComponent = parentSection
        }

        treesPanel.add(verticalSplitter, BorderLayout.CENTER)
        return treesPanel
    }

    private fun createTreeHeader(
        headerLabel: JLabel,
        descLabel: JLabel,
        descField: JBTextArea
    ) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        headerLabel.alignmentX = JPanel.LEFT_ALIGNMENT
        headerLabel.border = JBUI.Borders.empty(4, 0)
        add(headerLabel)
        descLabel.alignmentX = JPanel.LEFT_ALIGNMENT
        descLabel.border = JBUI.Borders.empty(2, 0)
        add(descLabel)
        val scrollPane = JScrollPane(descField).apply {
            alignmentX = JPanel.LEFT_ALIGNMENT
            preferredSize = Dimension(0, JBUI.scale(44))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(44))
        }
        add(scrollPane)
    }

    private fun createTreeWithToolbar(tree: JujutsuChangesTree, emptyLabel: JBLabel?): JComponent {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.CHANGES_VIEW_TOOLBAR,
            DefaultActionGroup().apply {
                val commonActionsManager = CommonActionsManager.getInstance()
                val treeExpander = tree.treeExpander
                add(commonActionsManager.createExpandAllAction(treeExpander, tree))
                add(commonActionsManager.createCollapseAllAction(treeExpander, tree))
                addSeparator()
                add(ActionManager.getInstance().getAction("ChangesView.GroupBy"))
            },
            true
        )
        toolbar.targetComponent = tree

        val panel = JPanel(BorderLayout())
        panel.add(toolbar.component, BorderLayout.NORTH)

        if (emptyLabel != null) {
            // Layered: tree scroll + empty label overlay
            val treeScroll = ScrollPaneFactory.createScrollPane(tree)
            val layered = JPanel(BorderLayout()).apply {
                add(treeScroll, BorderLayout.CENTER)
                add(emptyLabel, BorderLayout.NORTH)
            }
            panel.add(layered, BorderLayout.CENTER)
        } else {
            panel.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        }

        return panel
    }

    private fun createTransferBar() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(2, 0)
        add(Box.createHorizontalGlue())

        moveToParentAction = object : DumbAwareAction(
            JujutsuBundle.message("dialog.split.move.to.parent"),
            null,
            AllIcons.Actions.MoveDown
        ) {
            override fun actionPerformed(e: AnActionEvent) = moveToParent()

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = childTree.selectedChanges.isNotEmpty()
            }
        }

        moveToChildAction = object : DumbAwareAction(
            JujutsuBundle.message("dialog.split.move.to.child"),
            null,
            AllIcons.Actions.MoveUp
        ) {
            override fun actionPerformed(e: AnActionEvent) = moveToChild()

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = parentTree.selectedChanges.isNotEmpty()
            }
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.CHANGES_VIEW_TOOLBAR,
            DefaultActionGroup(moveToChildAction, moveToParentAction),
            true
        )
        toolbar.targetComponent = this
        add(toolbar.component)
        add(Box.createHorizontalGlue())
    }

    private fun createBottomSection() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        parallelCheckBox.alignmentX = JPanel.LEFT_ALIGNMENT
        parallelCheckBox.border = JBUI.Borders.empty(4, 0)
        add(parallelCheckBox)
    }

    private fun createSectionLabel(text: String) = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
        alignmentX = JLabel.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(4, 0)
    }

    private fun createEntryPane(entry: LogEntry) = IconAwareHtmlPane().apply {
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

    override fun doValidate(): ValidationInfo? {
        val parallel = parallelCheckBox.isSelected
        if (childChanges.isEmpty()) {
            val key = if (parallel) {
                "dialog.split.validation.child.empty.parallel"
            } else {
                "dialog.split.validation.child.empty"
            }
            return ValidationInfo(JujutsuBundle.message(key), childTree)
        }
        if (parentChanges.isEmpty()) {
            val key = if (parallel) {
                "dialog.split.validation.parent.empty.parallel"
            } else {
                "dialog.split.validation.parent.empty"
            }
            return ValidationInfo(JujutsuBundle.message(key), parentTree)
        }
        return null
    }

    override fun doOKAction() {
        val parentFilePaths = parentChanges.mapNotNull { it.filePath }
        val childDesc = childDescriptionField.text.trim()
        val originalDesc = sourceEntry.description.actual

        result = SplitSpec(
            revision = sourceEntry.id,
            filePaths = parentFilePaths,
            description = Description(parentDescriptionField.text.trim()),
            childDescription = if (childDesc != originalDesc) Description(childDesc) else null,
            parallel = parallelCheckBox.isSelected
        )
        super.doOKAction()
    }
}
