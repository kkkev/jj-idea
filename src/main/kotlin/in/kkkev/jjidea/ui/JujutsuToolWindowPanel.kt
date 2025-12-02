package `in`.kkkev.jjidea.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsListener
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import `in`.kkkev.jjidea.JujutsuVcs
import `in`.kkkev.jjidea.root
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Main panel for the Jujutsu tool window
 * Implements JJ's "describe-first" workflow with a tree view of changes
 */
class JujutsuToolWindowPanel(private val project: Project) : Disposable {
    private val panel = JPanel(BorderLayout())
    private val descriptionArea = JBTextArea(3, 50)
    private val currentChangeLabel = JBLabel("Working Copy (@)")

    // Tree for displaying changes
    private val changesTree: Tree
    private var currentChanges: List<Change> = emptyList()

    // Grouping state
    private var groupByDirectory = true

    private val vcs get() = JujutsuVcs.find(project)

    init {
        // Create the changes tree
        changesTree = Tree()
        changesTree.cellRenderer = JujutsuChangesTreeCellRenderer(project)
        changesTree.isRootVisible = true
        changesTree.showsRootHandles = true

        createUI()
        setupTreeInteractions()
        setupVcsListener()

        // Try to load immediately in case VCS is already available
        loadCurrentChanges()
    }

    private fun createUI() {
        panel.border = JBUI.Borders.empty(8)

        // Top panel: Current change info and description
        val topPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.anchor = GridBagConstraints.WEST
        gbc.insets = JBUI.insets(4)

        // Current change label
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 2
        gbc.weightx = 1.0
        currentChangeLabel.font = currentChangeLabel.font.deriveFont(13f)
        topPanel.add(currentChangeLabel, gbc)

        // Description label
        gbc.gridy = 1
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        topPanel.add(JBLabel("Description:"), gbc)

        // Description text area
        gbc.gridy = 2
        gbc.gridwidth = 2
        gbc.weightx = 1.0
        gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.BOTH
        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        descriptionArea.toolTipText = "Describe what you're working on (jj describe)"
        val scrollPane = ScrollPaneFactory.createScrollPane(descriptionArea)
        scrollPane.preferredSize = JBUI.size(400, 80)
        topPanel.add(scrollPane, gbc)

        // Buttons
        gbc.gridy = 3
        gbc.gridwidth = 2
        gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.NONE
        val buttonPanel = createButtonPanel()
        topPanel.add(buttonPanel, gbc)

        panel.add(topPanel, BorderLayout.NORTH)

        // Center: Changes tree with toolbar
        val changesPanel = JPanel(BorderLayout())

        // Toolbar
        val toolbar = createChangesToolbar()
        toolbar.targetComponent = changesTree  // Fix: Set target component to avoid warning
        changesPanel.add(toolbar.component, BorderLayout.NORTH)

        // Tree
        changesPanel.add(ScrollPaneFactory.createScrollPane(changesTree), BorderLayout.CENTER)

        panel.add(changesPanel, BorderLayout.CENTER)
    }

    private fun createChangesToolbar(): ActionToolbar {
        val group = DefaultActionGroup()

        // Refresh action
        group.add(object : DumbAwareAction("Refresh", "Refresh changes", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refresh()
            }
        })

        group.addSeparator()

        // Expand all
        group.add(object : DumbAwareAction("Expand All", "Expand all nodes", AllIcons.Actions.Expandall) {
            override fun actionPerformed(e: AnActionEvent) {
                TreeUtil.expandAll(changesTree)
            }
        })

        // Collapse all
        group.add(object : DumbAwareAction("Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall) {
            override fun actionPerformed(e: AnActionEvent) {
                TreeUtil.collapseAll(changesTree, 1)
            }
        })

        group.addSeparator()

        // Group by directory toggle
        group.add(object :
            ToggleAction("Group By Directory", "Group changes by directory", AllIcons.Actions.GroupByPackage) {
            override fun isSelected(e: AnActionEvent): Boolean = groupByDirectory

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                groupByDirectory = state
                rebuildTree()
            }
        })

        return ActionManager.getInstance().createActionToolbar(ActionPlaces.CHANGES_VIEW_TOOLBAR, group, true)
    }

    private fun setupVcsListener() {
        // Listen for VCS mapping changes to reload when Jujutsu VCS is enabled
        val connection = project.messageBus.connect(this)
        connection.subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, VcsListener {
            refresh()
        })
    }

    private fun setupTreeInteractions() {
        // Single-click: open file in preview tab
        changesTree.addTreeSelectionListener {
            val selectedChange = getSelectedChange()
            selectedChange?.let { openFileInPreview(it) }
        }

        // Mouse clicks: double-click shows diff
        changesTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) {
                    val selectedChange = getSelectedChange()
                    selectedChange?.let { showDiff(it) }
                }
            }
        })

        // F4 to open file in permanent tab
        changesTree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_F4) {
                    val selectedChange = getSelectedChange()
                    selectedChange?.let { openFilePermanent(it) }
                }
            }
        })

        // Right-click context menu
        changesTree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                showContextMenu(comp, x, y)
            }
        })

        // Enter key shows diff (like Git plugin)
        changesTree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    val selectedChange = getSelectedChange()
                    selectedChange?.let { showDiff(it) }
                }
            }
        })
    }

    private fun getSelectedChange(): Change? {
        val path = changesTree.selectionPath ?: return null
        val node = path.lastPathComponent
        return if (node is JujutsuChangesTreeModel.ChangeNode) {
            node.change
        } else {
            null
        }
    }

    private fun rebuildTree() {
        val modelBuilder = JujutsuChangesTreeModel(project, groupByDirectory)
        val model = modelBuilder.buildModel(currentChanges)
        changesTree.model = model

        // Expand the root and first level
        TreeUtil.expand(changesTree, 1)
    }

    private fun createButtonPanel(): JPanel {
        val buttonPanel = JPanel()
        buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.X_AXIS)

        // Describe button - updates the description of current working copy
        val describeButton = JButton("Describe")
        describeButton.toolTipText = "Set description for current change (jj describe)"
        describeButton.addActionListener {
            describeCurrentChange()
        }

        // New button - creates a new commit on top of current one
        val newButton = JButton("New Change")
        newButton.toolTipText = "Start a new change on top of current one (jj new)"
        newButton.addActionListener {
            createNewChange()
        }

        buttonPanel.add(describeButton)
        buttonPanel.add(Box.createHorizontalStrut(8))
        buttonPanel.add(newButton)
        buttonPanel.add(Box.createHorizontalGlue())

        return buttonPanel
    }

    private fun describeCurrentChange() {
        val description = descriptionArea.text.trim()
        if (description.isEmpty()) {
            JOptionPane.showMessageDialog(
                panel,
                "Please enter a description",
                "No Description",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        vcs?.let { vcsInstance ->
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = vcsInstance.commandExecutor.describe(description)

                ApplicationManager.getApplication().invokeLater {
                    if (result.isSuccess) {
                        refresh()
                    } else {
                        JOptionPane.showMessageDialog(
                            panel,
                            "Failed to describe change:\n${result.stderr}",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }
            }
        }
    }

    private fun createNewChange() {
        val vcsInstance = vcs ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = vcsInstance.commandExecutor.new()

            ApplicationManager.getApplication().invokeLater {
                if (result.isSuccess) {
                    descriptionArea.text = ""
                    refresh()
                    JOptionPane.showMessageDialog(
                        panel,
                        "New change created. You can now start working on the next task.",
                        "Success",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                } else {
                    JOptionPane.showMessageDialog(
                        panel,
                        "Failed to create new change:\n${result.stderr}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
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
        val vcsInstance = vcs ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            // Use same template format as JujutsuLogPanel for consistency
            // Parent format: "fullId~shortId, fullId~shortId" (~ separates full from short, comma separates parents)
            // Note: Truncation to 8 chars happens in rendering, not in template
            val template = """
                change_id ++ "\0" ++
                change_id.shortest() ++ "\0" ++
                commit_id ++ "\0" ++
                description ++ "\0" ++
                bookmarks ++ "\0" ++
                parents.map(|c| c.change_id() ++ "~" ++ c.change_id().shortest()).join(", ") ++ "\0" ++
                if(current_working_copy, "true", "false") ++ "\0" ++
                if(conflict, "true", "false") ++ "\0" ++
                if(empty, "true", "false") ++ "\0"
            """.trimIndent().replace("\n", " ")

            val result = vcsInstance.commandExecutor.log("@", template)

            ApplicationManager.getApplication().invokeLater {
                if (result.isSuccess) {
                    val entries = JujutsuLogParser.parseLog(result.stdout)
                    if (entries.isNotEmpty()) {
                        val entry = entries[0]

                        // Update the description text area
                        descriptionArea.text = entry.description

                        // Format change ID with theme-aware colors
                        val formattedChangeId = entry.getFormattedChangeId()
                        val changeIdHtml = JujutsuCommitFormatter.toHtml(formattedChangeId)

                        // Create HTML for label showing change ID, @ marker, and parents
                        val labelText = buildString {
                            append("<html>")
                            append(changeIdHtml)
                            append(" @ - Working Copy")

                            // Add parent IDs if present
                            if (entry.parentIds.isNotEmpty()) {
                                append("<br>")
                                append("<font size=-1>Parents: ")
                                entry.parentIds.forEachIndexed { index, parentFullId ->
                                    if (index > 0) append(", ")
                                    // Extract short prefix from the full ID (take first 2 chars as minimum)
                                    val shortPrefix = if (parentFullId.length >= 2) parentFullId.substring(0, 2) else parentFullId
                                    val parentFormatted = JujutsuCommitFormatter.formatChangeId(parentFullId, shortPrefix)
                                    append(JujutsuCommitFormatter.toHtml(parentFormatted))
                                }
                                append("</font>")
                            }

                            append("</html>")
                        }
                        currentChangeLabel.text = labelText
                    }
                }
            }
        }
    }

    private fun getWorkingCopyChanges(): List<Change> {
        val changeListManager = ChangeListManager.getInstance(project)
        return changeListManager.allChanges.toList()
    }

    private fun updateChangesView(changes: List<Change>) {
        currentChanges = changes
        rebuildTree()
    }

    private fun showDiff(change: Change) {
        // Load content in background thread to avoid EDT blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            val beforePath = change.beforeRevision?.file
            val afterPath = change.afterRevision?.file
            val fileName = afterPath?.name ?: beforePath?.name ?: "Unknown"

            // Load content in background
            val beforeContent = change.beforeRevision?.content ?: ""
            val afterVirtualFile = afterPath?.let { LocalFileSystem.getInstance().findFileByPath(it.path) }
            val afterContent = if (afterVirtualFile == null) {
                change.afterRevision?.content ?: ""
            } else {
                null  // Will use VirtualFile directly
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
                    "${beforePath?.name ?: "Before"} (@-)",
                    "${afterPath?.name ?: "After"} (@)"
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
        val showDiffAction = object : DumbAwareAction("Show Diff (Click)") {
            override fun actionPerformed(e: AnActionEvent) {
                showDiff(selectedChange)
            }
        }
        group.add(showDiffAction)

        val openFileAction = object : DumbAwareAction("Open File") {
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

    override fun dispose() {
        // Nothing to dispose
    }
}
