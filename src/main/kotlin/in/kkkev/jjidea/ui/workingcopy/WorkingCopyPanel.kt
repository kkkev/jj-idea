package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.JujutsuChangesTree
import `in`.kkkev.jjidea.ui.StringBuilderHtmlTextCanvas
import `in`.kkkev.jjidea.ui.append
import `in`.kkkev.jjidea.vcs.actions.requestDescription
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.TreePath

/**
 * Main panel for the Jujutsu tool window
 * Implements JJ's "describe-first" workflow with a tree view of changes
 *
 * TODO: Localise strings in here
 */
class WorkingCopyPanel(private val project: Project, val repo: JujutsuRepository) : Disposable {
    companion object {
        private const val COLLAPSED_PATHS_KEY_PREFIX = "JujutsuToolWindow.CollapsedPaths"
    }

    private val log = Logger.getInstance(javaClass)

    private val collapsedPathsKey = "$COLLAPSED_PATHS_KEY_PREFIX.${project.locationHash}:${repo.relativePath}"

    private val panel = JPanel(BorderLayout())

    // Track whether description has been modified since last load
    private var isDescriptionModified = false
    private var persistedDescription = Description.EMPTY

    // References to buttons that need to be updated when modification state changes
    private lateinit var describeButton: JButton
    private lateinit var revertButton: JButton

    private val descriptionArea = JBTextArea().apply {
        rows = 4 // Explicitly set number of visible rows
        columns = 50
        lineWrap = true
        wrapStyleWord = true
        isEditable = true
        toolTipText = JujutsuBundle.message("toolwindow.description.tooltip")
        // TODO Show "(empty)" in italics if empty - as a "default" or "exemplar" value

        // Track modifications to show visual feedback
        document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = checkModified()

                override fun removeUpdate(e: DocumentEvent?) = checkModified()

                override fun changedUpdate(e: DocumentEvent?) = checkModified()

                private fun checkModified() {
                    val newModified = text != persistedDescription.actual
                    if (newModified != isDescriptionModified) {
                        isDescriptionModified = newModified
                        updateDescriptionLabel()
                    }
                }
            }
        )
    }

    private val currentChangeLabel = JBLabel(JujutsuBundle.message("toolwindow.workingcopy.label")).apply {
        // TODO Pick font size according to IntelliJ appearance settings
        font = font.deriveFont(13f)
    }

    private val descriptionLabel = JBLabel(JujutsuBundle.message("toolwindow.description.label"))

    // Tree for displaying changes (using IntelliJ's built-in changes tree)
    private val changesTree = JujutsuChangesTree(project)

    // Track paths that user explicitly collapsed (persisted across sessions)
    private val userCollapsedPaths: MutableSet<String>

    // Flag to ignore programmatic expand/collapse events
    // Start as true to ignore events during initialization, then enable tracking after tree settles
    private var ignoreExpansionEvents = true

    // Note: No longer used - we call findRequired() directly where needed to make intent clear

    init {
        userCollapsedPaths = loadCollapsedPaths()

        createUI()
        setupTreeInteractions()
        setupTreeExpansionTracking()
        setupVfsListener()
    }

    private fun createUI() {
        // Overall container with all sections
        val mainPanel = JPanel(BorderLayout())

        // Working copy toolbar at the very top
        val workingCopyToolbar = createWorkingCopyToolbar(mainPanel)
        mainPanel.add(workingCopyToolbar.component, BorderLayout.NORTH)

        // Middle section: Current change info and description
        val infoPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 8)
        }

        val topPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(1)
        }

        // Current change label
        topPanel.add(
            currentChangeLabel,
            gbc.apply {
                gridx = 0
                gridy = 0
                gridwidth = 2
                weightx = 1.0
            }
        )

        // Description label with inline action buttons
        val descriptionHeaderPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(descriptionLabel)
            add(Box.createHorizontalStrut(8))
            add(createDescribeButton())
            add(Box.createHorizontalStrut(4))
            add(createRevertButton())
            add(Box.createHorizontalGlue())
        }

        topPanel.add(
            descriptionHeaderPanel,
            gbc.apply {
                gridy = 1
                gridwidth = 2
                weightx = 1.0
            }
        )

        // Description text area with scroll pane
        val scrollPane = ScrollPaneFactory.createScrollPane(descriptionArea).apply {
            minimumSize = JBUI.size(200, 70)
            preferredSize = JBUI.size(400, 90)
        }
        topPanel.add(
            scrollPane,
            gbc.apply {
                gridy = 2
                gridwidth = 2
                weightx = 1.0
                weighty = 0.0
                fill = GridBagConstraints.BOTH
            }
        )

        infoPanel.add(topPanel, BorderLayout.CENTER)
        mainPanel.add(infoPanel, BorderLayout.CENTER)

        panel.add(mainPanel, BorderLayout.NORTH)

        // Center: Changes tree with toolbar
        val changesPanel = JPanel(BorderLayout())

        // Toolbar
        val toolbar = createChangesToolbar().apply {
            targetComponent = changesTree // Fix: Set target component to avoid warning
        }
        changesPanel.add(toolbar.component, BorderLayout.NORTH)

        // Tree
        changesPanel.add(ScrollPaneFactory.createScrollPane(changesTree), BorderLayout.CENTER)

        panel.add(changesPanel, BorderLayout.CENTER)
    }

    private fun createWorkingCopyToolbar(owner: JComponent): ActionToolbar {
        val group = DefaultActionGroup()

        // New Change action - primary workflow operation
        group.add(
            object : DumbAwareAction(
                JujutsuBundle.message("button.newchange"),
                JujutsuBundle.message("button.newchange.tooltip"),
                AllIcons.General.Add
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    createNewChange()
                }
            }
        )

        // Placeholder for future actions: Edit, Abandon, Rebase, etc.

        return ActionManager.getInstance()
            .createActionToolbar(
                "JujutsuWorkingCopyToolbar",
                group,
                true
            ).apply {
                targetComponent = owner
            }
    }

    private fun createDescribeButton(): JButton {
        describeButton = JButton(JujutsuBundle.message("button.describe")).apply {
            toolTipText = JujutsuBundle.message("button.describe.tooltip")
            icon = AllIcons.Actions.MenuSaveall
            addActionListener { describeCurrentChange() }
            // Start disabled, will be enabled when description is modified
            isEnabled = false
        }
        return describeButton
    }

    private fun createRevertButton(): JButton {
        revertButton = JButton("Revert").apply {
            toolTipText = "Reload description from working copy"
            icon = AllIcons.Actions.Rollback
            addActionListener { revertDescription() }
            // Start disabled, will be enabled when description is modified
            isEnabled = false
        }
        return revertButton
    }

    private fun createChangesToolbar(): ActionToolbar {
        val group = DefaultActionGroup()

        // Refresh action
        group.add(
            object : DumbAwareAction(
                JujutsuBundle.message("button.refresh"),
                JujutsuBundle.message("button.refresh.tooltip"),
                AllIcons.Actions.Refresh
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    refresh()
                }
            }
        )

        group.addSeparator()

        // Tree expander actions (expand all / collapse all) provided by the tree
        val treeExpander = changesTree.treeExpander
        val commonActionsManager = CommonActionsManager.getInstance()
        group.add(commonActionsManager.createExpandAllAction(treeExpander, changesTree))
        group.add(commonActionsManager.createCollapseAllAction(treeExpander, changesTree))

        group.addSeparator()

        // Grouping actions provided by the tree's grouping support
        group.add(ActionManager.getInstance().getAction("ChangesView.GroupBy"))

        return ActionManager.getInstance().createActionToolbar(ActionPlaces.CHANGES_VIEW_TOOLBAR, group, true)
    }

    private fun setupTreeExpansionTracking() {
        changesTree.addTreeExpansionListener(
            object : TreeExpansionListener {
                private fun update(
                    event: TreeExpansionEvent,
                    consumer: (String) -> Unit
                ) {
                    if (ignoreExpansionEvents) {
                        log.debug("Ignoring collapse (programmatic)")
                    } else {
                        getPathIdentifier(event.path)?.let {
                            consumer(it)
                            saveCollapsedPaths()
                        }
                    }
                }

                override fun treeCollapsed(event: TreeExpansionEvent) =
                    update(event) {
                        log.debug("User collapsed: $it")
                        userCollapsedPaths.add(it)
                    }

                override fun treeExpanded(event: TreeExpansionEvent) =
                    update(event) {
                        log.debug("User expanded: $it")
                        userCollapsedPaths.remove(it)
                    }
            }
        )
    }

    private fun getPathIdentifier(treePath: TreePath) =
        treePath.path
            .drop(1) // Skip root
            .mapNotNull { component ->
                when (component) {
                    is ChangesBrowserNode<*> -> component.textPresentation ?: component.toString()
                    // is DefaultMutableTreeNode -> component.userObject?.toString()
                    else -> null
                }
            }.takeIf { it.isNotEmpty() }
            ?.joinToString("/")

    private fun setupVfsListener() {
        // Listen to ChangeListManager updates triggered by VCS change detection
        project.messageBus.connect(this).subscribe(
            ChangeListListener.TOPIC,
            object : ChangeListListener {
                override fun changeListUpdateDone() {
                    refresh()
                }
            }
        )
    }

    /**
     * Update description and label from model.
     */
    fun update(logEntry: LogEntry) {
        ApplicationManager.getApplication().invokeLater {
            persistedDescription = logEntry.description
            descriptionArea.text = persistedDescription.actual
            isDescriptionModified = false
            updateDescriptionLabel()
            updateWorkingCopyLabel(logEntry)

            // Update file changes
        }
        // TODO What about file changes?
        // updateChangesView(model.fileChanges)
    }

    private fun setupTreeInteractions() {
        // Single-click: open file in preview tab (only on user clicks, not selection changes)
        changesTree.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 1 && !e.isPopupTrigger) {
                        getSelectedChange()?.let { openFileInPreview(it) }
                    }
                }
            }
        )

        // Double-click handler: show diff
        changesTree.setDoubleClickHandler { _ ->
            getSelectedChange()?.let {
                showDiff(it)
                true
            } ?: false
        }

        // Enter key handler: show diff
        changesTree.setEnterKeyHandler {
            getSelectedChange()?.let {
                showDiff(it)
                true
            } ?: false
        }

        // Right-click context menu
        changesTree.addMouseListener(
            object : PopupHandler() {
                override fun invokePopup(
                    comp: Component,
                    x: Int,
                    y: Int
                ) {
                    showContextMenu(comp, x, y)
                }
            }
        )
    }

    private fun getSelectedChange() = changesTree.selectedChanges.firstOrNull()

    private fun updateDescriptionLabel() {
        val baseLabel = JujutsuBundle.message("toolwindow.description.label")
        descriptionLabel.text = if (isDescriptionModified) "$baseLabel *" else baseLabel
        // Update button states
        describeButton.isEnabled = isDescriptionModified
        revertButton.isEnabled = isDescriptionModified
    }

    private fun revertDescription() {
        descriptionArea.text = persistedDescription.actual
        isDescriptionModified = false
        updateDescriptionLabel()
    }

    private fun describeCurrentChange() {
        val description = Description(descriptionArea.text.trim())

        repo.commandExecutor
            .createCommand { describe(description) }
            .onSuccess {
                // Update persisted description and reset modified flag
                persistedDescription = description
                isDescriptionModified = false
                updateDescriptionLabel()

                // TODO Should this select?
                repo.invalidate()
            }.onFailure {
                JOptionPane.showMessageDialog(
                    panel,
                    JujutsuBundle.message("dialog.describe.error.message", stderr),
                    JujutsuBundle.message("dialog.describe.error.title"),
                    JOptionPane.ERROR_MESSAGE
                )
            }.executeAsync()
    }

    private fun createNewChange() {
        // Show modal dialog to get description for the new change
        val description = project.requestDescription("dialog.newchange.input") ?: return

        repo.commandExecutor.createCommand {
            new(description = description)
        }.onSuccess {
            // Clear the description area since we've created a new change
            persistedDescription = Description.EMPTY
            descriptionArea.text = ""
            isDescriptionModified = false
            updateDescriptionLabel()

            // TODO Should this also select?
            repo.invalidate()
        }.onFailure {
            JOptionPane.showMessageDialog(
                panel,
                JujutsuBundle.message("dialog.newchange.error.message", stderr),
                JujutsuBundle.message("dialog.newchange.error.title"),
                JOptionPane.ERROR_MESSAGE
            )
        }.executeAsync()
    }

    fun getContent(): JComponent = panel

    fun refresh() {
        loadCurrentChanges()
        loadCurrentDescription()
    }

    private fun loadCurrentChanges() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val changes = getWorkingCopyChanges()

            ApplicationManager.getApplication().invokeLater {
                updateChangesView(changes)
            }
        }
    }

    private fun loadCurrentDescription() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = repo.logService.getLog(WorkingCopy)
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { entries ->
                    entries.firstOrNull()?.let { entry ->
                        // Update the description text area
                        persistedDescription = entry.description
                        descriptionArea.text = persistedDescription.actual
                        isDescriptionModified = false
                        updateDescriptionLabel()
                        updateWorkingCopyLabel(entry)
                    }
                }.onFailure { error ->
                    log.error("Failed to load current description", error)
                }
            }
        }
    }

    private fun updateWorkingCopyLabel(entry: LogEntry) {
        // Create HTML for label showing change ID, @ marker, and parents
        val labelText = buildString {
            val canvas = StringBuilderHtmlTextCanvas(this)

            append("<html>")
            canvas.append(entry.changeId)

            // Add parent IDs if present
            if (entry.parentIds.isNotEmpty()) {
                append("<br>")
                append("<font size=-1>")
                append(JujutsuBundle.message("toolwindow.parents.label"))
                append(" ")
                entry.parentIds.forEachIndexed { index, parentId ->
                    if (index > 0) append(", ")
                    canvas.append(parentId)
                }
                append("</font>")
            }

            append("</html>")
        }
        currentChangeLabel.text = labelText
    }

    private fun getWorkingCopyChanges() = ChangeListManager.getInstance(project).allChanges.toList()

    private fun updateChangesView(changes: List<Change>) {
        if (changes != changesTree.changes) {
            changesTree.setChangesToDisplay(changes)

            // After refresh: expand all nodes, then re-collapse user's explicit collapses
            changesTree.invokeAfterRefresh {
                // Ignore expansion events during programmatic operations
                ignoreExpansionEvents = true

                // Expand all nodes by default
                changesTree.treeExpander.expandAll()

                // Re-collapse paths that user explicitly collapsed
                reapplyUserCollapses()

                // Re-enable expansion event tracking after a delay to let event queue settle
                ApplicationManager.getApplication().invokeLater {
                    log.info("Re-enabling expansion event tracking")
                    ignoreExpansionEvents = false
                }
            }
        }
    }

    private fun reapplyUserCollapses() {
        // Walk the tree and collapse paths that match user's collapsed set
        log.info("Reapplying user collapses (${userCollapsedPaths.size} paths)")
        changesTree.model.root?.let {
            collapseMatchingPaths(TreePath(it))
        }
    }

    private fun collapseMatchingPaths(path: TreePath) {
        val identifier = getPathIdentifier(path)
        if (identifier != null && identifier in userCollapsedPaths) {
            log.info("Re-collapsing: $identifier")
            changesTree.collapsePath(path)
        }

        // Recursively check children
        val node = path.lastPathComponent
        val childCount = changesTree.model.getChildCount(node)
        for (i in 0 until childCount) {
            val child = changesTree.model.getChild(node, i)
            collapseMatchingPaths(path.pathByAddingChild(child))
        }
    }

    private fun showDiff(change: Change) {
        // Load content in background thread to avoid EDT blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            val beforePath = change.beforeRevision?.file
            val afterPath = change.afterRevision?.file
            val fileName = afterPath?.name ?: beforePath?.name ?: JujutsuBundle.message("diff.title.unknown")

            // Load content in background
            val beforeContent = change.beforeRevision?.content ?: ""
            val afterVirtualFile = afterPath?.let { LocalFileSystem.getInstance().findFileByPath(it.path) }
            val afterContent = if (afterVirtualFile == null) {
                change.afterRevision?.content ?: ""
            } else {
                null // Will use VirtualFile directly
            }

            // Now show diff on EDT with loaded content
            ApplicationManager.getApplication().invokeLater {
                val contentFactory = DiffContentFactory.getInstance()
                val diffManager = DiffManager.getInstance()

                // Create diff content - use string content only, not FilePath, to avoid re-reading
                val content1 = if (beforePath != null && beforeContent.isNotEmpty()) {
                    // Create content from string without FilePath to ensure we use loaded content
                    contentFactory.create(project, beforeContent)
                } else if (beforePath != null) {
                    contentFactory.createEmpty()
                } else {
                    contentFactory.createEmpty()
                }

                val content2 = if (afterVirtualFile != null && afterVirtualFile.exists()) {
                    // For working copy, use the actual file from disk to allow editing
                    contentFactory.create(project, afterVirtualFile)
                } else if (afterPath != null && afterContent != null) {
                    contentFactory.create(project, afterContent)
                } else {
                    contentFactory.createEmpty()
                }

                val diffRequest = SimpleDiffRequest(
                    fileName,
                    content1,
                    content2,
                    "${beforePath?.name ?: JujutsuBundle.message("diff.title.before")} (@-)",
                    "${afterPath?.name ?: JujutsuBundle.message("diff.title.after")} (@)"
                )

                diffManager.showDiff(project, diffRequest)
            }
        }
    }

    private fun openFileInPreview(change: Change) {
        // Open file in preview tab (respects IDE preview tab settings)
        val filePath = change.afterRevision?.file ?: change.beforeRevision?.file ?: return
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath.path) ?: return
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, virtualFile).navigate(true)
        }
    }

    private fun openFilePermanent(change: Change) {
        // Open file in permanent tab (not preview)
        val filePath = change.afterRevision?.file ?: change.beforeRevision?.file ?: return
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath.path) ?: return
        ApplicationManager.getApplication().invokeLater {
            val fileEditorManager = FileEditorManager.getInstance(project)
            fileEditorManager.openFile(virtualFile, true)
            OpenFileDescriptor(project, virtualFile).navigate(true)
        }
    }

    private fun showContextMenu(component: Component, x: Int, y: Int) {
        val selectedChange = getSelectedChange() ?: return

        val actionManager = ActionManager.getInstance()
        val group = DefaultActionGroup()

        // Add common VCS actions with keyboard shortcuts displayed
        val showDiffAction =
            object : DumbAwareAction(
                JujutsuBundle.message("action.show.diff"),
                null,
                AllIcons.Actions.Diff
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    showDiff(selectedChange)
                }
            }
        group.add(showDiffAction)

        val openFileAction = object : DumbAwareAction(
            JujutsuBundle.message("action.open.file"),
            null,
            AllIcons.Actions.EditSource
        ) {
            init {
                // Set keyboard shortcuts - will be displayed in menu
                shortcutSet = CustomShortcutSet(
                    KeyboardShortcut(KeyStroke.getKeyStroke("F4"), null),
                    KeyboardShortcut(KeyStroke.getKeyStroke("ENTER"), null)
                )
            }

            override fun actionPerformed(e: AnActionEvent) {
                openFilePermanent(selectedChange)
            }
        }
        group.add(openFileAction)

        val popupMenu = actionManager.createActionPopupMenu(ActionPlaces.UNKNOWN, group)
        popupMenu.component.show(component, x, y)
    }

    private fun loadCollapsedPaths(): MutableSet<String> {
        val properties = PropertiesComponent.getInstance(project)
        val stored = properties.getValue(collapsedPathsKey) ?: return mutableSetOf()
        return stored.split("|").filter { it.isNotEmpty() }.toMutableSet()
    }

    private fun saveCollapsedPaths() {
        val properties = PropertiesComponent.getInstance(project)
        properties.setValue(collapsedPathsKey, userCollapsedPaths.joinToString("|"))
    }

    override fun dispose() {
        saveCollapsedPaths()
    }
}
