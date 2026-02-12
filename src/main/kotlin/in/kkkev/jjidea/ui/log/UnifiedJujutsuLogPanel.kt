package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.CommitTablePanel
import `in`.kkkev.jjidea.vcs.jujutsuRepositories
import javax.swing.Box
import javax.swing.JPanel

/**
 * Unified panel for Jujutsu commit log UI that shows commits from all repositories.
 *
 * Layout:
 * - NORTH: Toolbar (refresh, filters including root filter)
 * - CENTER: Splitter with log table (top) and details panel (bottom)
 *
 * Unlike the single-root JujutsuLogPanel, this panel:
 * - Loads commits from all configured JJ repositories
 * - Provides a root filter to show/hide commits by repository
 * - Displays root indicators in the log entries
 */
class UnifiedJujutsuLogPanel(private val project: Project) :
    CommitTablePanel<UnifiedJujutsuLogDataLoader.Data>(project, "JujutsuLogToolbar", {
        UnifiedJujutsuLogDataLoader(
            project,
            { project.jujutsuRepositories },
            it
        )
    }) {
    private val log = Logger.getInstance(javaClass)

    // Root filter component (created lazily, only shown if multiple roots)
    private var rootFilterComponent: JujutsuRootFilterComponent? = null

    init {

        // Load saved column widths from settings
        logTable.loadColumnWidths()

        // Subscribe to state changes from all repositories
        setupStateListener()

        // Listen for change selection requests
        project.stateModel.changeSelection.connect(this) { key ->
            logTable.requestSelection(key)
            dataLoader.refresh()
        }

        log.info("UnifiedJujutsuLogPanel initialized for project: ${project.name}")
    }

    private fun setupStateListener() {
        // Listen for repository state changes to refresh
        // (Selection handling is done by the data loader)
        project.stateModel.repositoryStates.connect(this) { _ ->
            refresh()
            updateRootFilterVisibility()
        }
    }

    override fun onDataLoaded(data: UnifiedJujutsuLogDataLoader.Data) {
        logTable.setEntries(data.entries)
        logTable.updateGraph(data.graphNodes)
        updateRootFilterVisibility()
    }

    /**
     * Update root filter and gutter visibility based on whether there are multiple roots.
     */
    private fun updateRootFilterVisibility() {
        val hasMultipleRoots = logTable.logModel.getAllRoots().size > 1

        // Update root filter visibility
        rootFilterComponent?.let { component ->
            component.isVisible = hasMultipleRoots
        }

        // Update gutter column visibility
        val wasGutterVisible = columnManager.showRootGutterColumn
        columnManager.showRootGutterColumn = hasMultipleRoots

        // Rebuild column visibility if gutter state changed
        if (wasGutterVisible != hasMultipleRoots) {
            updateColumnVisibility()
        }
    }

    override fun createOtherFilterComponents(filterPanel: JPanel) {
        // Root filter (only shown when multiple roots)
        rootFilterComponent = JujutsuRootFilterComponent(logTable.logModel).apply {
            initUi()
            initialize()
            // Initially hidden, will show when data loads if multiple roots
            isVisible = false
        }
        add(rootFilterComponent)
        add(Box.createHorizontalStrut(5))
    }

    override fun updateTableStuff() {
        updateColumnVisibility()
        logTable.updateGraph(logTable.graphNodes) // Refresh rendering
    }

    /**
     * Update table column visibility based on column manager settings.
     */
    override fun updateColumnVisibility() {
        super.updateColumnVisibility()
        // Restore saved column widths
        logTable.loadColumnWidths()
    }

    override fun dispose() {
        log.info("UnifiedJujutsuLogPanel disposed")
        detailsPanel.dispose()
        // Other cleanup will happen automatically
    }
}
