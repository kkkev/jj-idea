package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.JujutsuChangesTree
import `in`.kkkev.jjidea.vcs.filePath
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.TreePath

/**
 * Unified working copy panel with repository grouping in the changes tree.
 * Combines all repositories into a single view with per-repo controls.
 *
 * The dropdown selector is the authoritative way to choose which repo's
 * working copy to edit. The tree is purely for viewing and navigating changes.
 */
class UnifiedWorkingCopyPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    companion object {
        private const val COLLAPSED_PATHS_KEY_PREFIX = "JujutsuToolWindow.CollapsedPaths"
    }

    private val log = Logger.getInstance(javaClass)

    private val collapsedPathsKey = "$COLLAPSED_PATHS_KEY_PREFIX.${project.locationHash}"

    // Per-repo description state
    private val descriptionStates = mutableMapOf<DescriptionState.Key, DescriptionState>()

    // UI Components
    private val changesTree = JujutsuChangesTree(project)
    private val controlsPanel = WorkingCopyControlsPanel(project)
    private val emptyStatePanel = createEmptyStatePanel()

    // Card layout for switching between content and empty state
    private val contentPanel = JPanel(BorderLayout())
    private val cardPanel = JPanel(CardLayout())

    // Track paths that user explicitly collapsed (persisted across sessions)
    private val userCollapsedPaths: MutableSet<String>

    // Flag to ignore programmatic expand/collapse events
    private var ignoreExpansionEvents = true

    init {
        userCollapsedPaths = loadCollapsedPaths()

        // Setup controls panel state provider (keyed by repo path for stability)
        controlsPanel.stateProvider = { repo ->
            descriptionStates.getOrPut(DescriptionState.Key(repo.relativePath)) { DescriptionState() }
        }

        // Handle dropdown selection
        controlsPanel.onRepositorySelected = { repo ->
            controlsPanel.boundRepository = repo
            project.stateModel.repositoryStates.value.find { it.repo == repo }?.let {
                controlsPanel.update(it)
            }
        }

        createUI()
        setupTreeInteractions()
        setupTreeExpansionTracking()
        setupVfsListener()
        subscribeToStateModel()
    }

    private fun createUI() {
        // Build the main content panel
        val mainPanel = JPanel(BorderLayout())

        // Top: Working copy toolbar (New Change action)
        val workingCopyToolbar = controlsPanel.createToolbar(mainPanel)
        mainPanel.add(workingCopyToolbar.component, BorderLayout.NORTH)

        // Center: Splitter with changes tree on top, controls below
        // Using IntelliJ Splitter instead of JSplitPane for better theming
        val splitter = Splitter(true, 0.6f).apply {
            dividerWidth = 3
        }

        // Changes tree with toolbar
        val changesPanel = JPanel(BorderLayout())
        val changesToolbar = createChangesToolbar().apply {
            targetComponent = changesTree
        }
        changesPanel.add(changesToolbar.component, BorderLayout.NORTH)
        changesPanel.add(ScrollPaneFactory.createScrollPane(changesTree), BorderLayout.CENTER)

        splitter.firstComponent = changesPanel

        // Bottom: Controls panel (dropdown selects repo, tree is just for viewing)
        splitter.secondComponent = controlsPanel

        mainPanel.add(splitter, BorderLayout.CENTER)

        contentPanel.add(mainPanel, BorderLayout.CENTER)

        // Setup card panel for switching between content and empty state
        cardPanel.add(contentPanel, "content")
        cardPanel.add(emptyStatePanel, "empty")

        add(cardPanel, BorderLayout.CENTER)
    }

    private fun createChangesToolbar(): ActionToolbar {
        // Use DefaultActionGroup (not BackgroundActionGroup) because this toolbar contains
        // platform tree expander actions that access TreeUI in update() and require EDT
        val group = DefaultActionGroup()

        // Refresh action
        group.add(object : DumbAwareAction(
            JujutsuBundle.message("button.refresh"),
            JujutsuBundle.message("button.refresh.tooltip"),
            AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                refresh()
            }
        })

        group.addSeparator()

        // Tree expander actions
        val treeExpander = changesTree.treeExpander
        val commonActionsManager = CommonActionsManager.getInstance()
        group.add(commonActionsManager.createExpandAllAction(treeExpander, changesTree))
        group.add(commonActionsManager.createCollapseAllAction(treeExpander, changesTree))

        group.addSeparator()

        // Grouping actions
        group.add(ActionManager.getInstance().getAction("ChangesView.GroupBy"))

        return ActionManager.getInstance().createActionToolbar(ActionPlaces.CHANGES_VIEW_TOOLBAR, group, true)
    }

    private fun createEmptyStatePanel(): JPanel = JPanel(BorderLayout()).apply {
        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(20)

            val label = JBLabel(JujutsuBundle.message("workingcopy.empty.message"))
            label.alignmentX = CENTER_ALIGNMENT
            add(label)

            add(Box.createVerticalStrut(8))

            val link = HyperlinkLabel(JujutsuBundle.message("workingcopy.empty.link"))
            link.alignmentX = CENTER_ALIGNMENT
            link.addHyperlinkListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    project,
                    "project.propVCSSupport.DirectoryMappings"
                )
            }
            add(link)
        }

        add(centerPanel, BorderLayout.CENTER)
    }

    private fun subscribeToStateModel() {
        project.stateModel.repositoryStates.connect(this) { _, new ->
            ApplicationManager.getApplication().invokeLater {
                // Update UI based on whether we have any repos
                val hasRepos = new.isNotEmpty()
                val cardLayout = cardPanel.layout as CardLayout
                cardLayout.show(cardPanel, if (hasRepos) "content" else "empty")

                if (hasRepos) {
                    // Update the dropdown with available repos
                    val sortedRepos = new.map { it.repo }.sortedBy { it.relativePath }
                    controlsPanel.updateAvailableRepositories(sortedRepos)

                    // Update controls if the current repo was updated
                    val currentRepo = controlsPanel.boundRepository
                    new.find { it.repo == currentRepo }?.let { entry ->
                        controlsPanel.update(entry)
                    }

                    // If no repo is bound, select the first one
                    if (currentRepo == null || new.none { it.repo == currentRepo }) {
                        controlsPanel.boundRepository = new.firstOrNull()?.repo
                        new.firstOrNull()?.let { controlsPanel.update(it) }
                    }
                }
            }
        }

        // Subscribe to change selection for programmatic repo selection
        project.stateModel.changeSelection.connect(this) { key ->
            controlsPanel.boundRepository = key.repo
            // Also update from state model
            project.stateModel.repositoryStates.value.find { it.repo == key.repo }?.let {
                controlsPanel.update(it)
            }
        }
    }

    private fun setupTreeExpansionTracking() {
        changesTree.addTreeExpansionListener(object : TreeExpansionListener {
            private fun update(event: TreeExpansionEvent, consumer: (String) -> Unit) {
                if (ignoreExpansionEvents) {
                    log.debug("Ignoring collapse (programmatic)")
                } else {
                    getPathIdentifier(event.path)?.let {
                        consumer(it)
                        saveCollapsedPaths()
                    }
                }
            }

            override fun treeCollapsed(event: TreeExpansionEvent) = update(event) {
                log.debug("User collapsed: $it")
                userCollapsedPaths.add(it)
            }

            override fun treeExpanded(event: TreeExpansionEvent) = update(event) {
                log.debug("User expanded: $it")
                userCollapsedPaths.remove(it)
            }
        })
    }

    private fun getPathIdentifier(treePath: TreePath) =
        treePath.path
            .drop(1) // Skip root
            .mapNotNull { component ->
                when (component) {
                    is ChangesBrowserNode<*> -> component.textPresentation ?: component.toString()
                    else -> null
                }
            }.takeIf { it.isNotEmpty() }
            ?.joinToString("/")

    private fun setupVfsListener() {
        project.messageBus.connect(this).subscribe(
            ChangeListListener.TOPIC,
            object : ChangeListListener {
                override fun changeListUpdateDone() {
                    reloadChangesFromCache()
                }
            }
        )
    }

    private fun setupTreeInteractions() {
        // Single-click: open file in preview tab (unique to working copy panel)
        changesTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 1 && !e.isPopupTrigger) {
                    getSelectedChange()?.let { openFileInPreview(it) }
                }
            }
        })

        changesTree.installHandlers()
    }

    private fun getSelectedChange() = changesTree.selectedChanges.firstOrNull()

    /**
     * Refresh button handler - triggers full refresh including external changes.
     *
     * Steps:
     * 1. VFS sync to detect external file changes
     * 2. Mark all repo directories dirty to trigger ChangeProvider
     * 3. Invalidate repositoryStates to reload descriptions
     * 4. Reload from cache (the listener chain may also trigger reloadChangesFromCache)
     */
    fun refresh() {
        val repos = project.stateModel.initializedRoots.value.map { it.directory }
        if (repos.isNotEmpty()) {
            // Sync VFS for external changes (async)
            VfsUtil.markDirtyAndRefresh(true, true, true, *repos.toTypedArray())

            // Mark all repo directories dirty to trigger ChangeProvider refresh
            val dirtyScopeManager = VcsDirtyScopeManager.getInstance(project)
            repos.forEach { dir -> dirtyScopeManager.dirDirtyRecursively(dir) }
        }

        // Invalidate repositoryStates to reload descriptions and change IDs
        project.stateModel.repositoryStates.invalidate()

        // Also reload changes directly - the listener chain may be slow or not fire
        reloadChangesFromCache()
    }

    /**
     * Called by ChangeListListener when changes are detected.
     * Reads from ChangeListManager cache and updates the UI.
     */
    private fun reloadChangesFromCache() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val changes = ChangeListManager.getInstance(project).allChanges.toList()

            ApplicationManager.getApplication().invokeLater {
                updateChangesView(changes)
            }
        }
    }

    private fun updateChangesView(changes: List<Change>) {
        if (changes != changesTree.changes) {
            changesTree.setChangesToDisplay(changes)

            changesTree.invokeAfterRefresh {
                ignoreExpansionEvents = true
                changesTree.treeExpander.expandAll()
                reapplyUserCollapses()

                ApplicationManager.getApplication().invokeLater {
                    log.info("Re-enabling expansion event tracking")
                    ignoreExpansionEvents = false
                }
            }
        }
    }

    private fun reapplyUserCollapses() {
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

        val node = path.lastPathComponent
        val childCount = changesTree.model.getChildCount(node)
        for (i in 0 until childCount) {
            val child = changesTree.model.getChild(node, i)
            collapseMatchingPaths(path.pathByAddingChild(child))
        }
    }

    private fun openFileInPreview(change: Change) {
        val virtualFile = change.filePath?.virtualFile ?: return
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, virtualFile).navigate(true)
        }
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
