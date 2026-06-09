package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.settings.LogWindowConfig
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories
import javax.swing.Box
import javax.swing.JPanel

/**
 * Unified panel for Jujutsu commit log UI that shows commits from all (or a selected subset of) repositories.
 *
 * Layout:
 * - NORTH: Toolbar (refresh, filters including root filter)
 * - CENTER: Splitter with log table (top) and details panel (bottom)
 *
 * Unlike the single-root JujutsuLogPanel, this panel:
 * - Loads commits from the repos specified by [config] (all repos if none selected)
 * - Provides a root filter to show/hide commits by repository
 * - Displays root indicators in the log entries
 * - Persists column visibility/widths, filter state, and details position per-window into [config].
 */
class UnifiedJujutsuLogPanel(project: Project, val config: LogWindowConfig) :
    CommitTablePanel<UnifiedJujutsuLogDataLoader.Data>(
        project,
        "JujutsuLogToolbar",
        { UnifiedJujutsuLogDataLoader(project, { config.selectedRepos(project.initialisedJujutsuRepositories) }, it) },
        initialDetailsOnRight = config.detailsOnRight,
        configureColumnManager = { it.loadFrom(config) }
    ) {
    private val log = Logger.getInstance(javaClass)

    // Root filter component (created lazily, only shown if multiple roots)
    private var rootFilterComponent: JujutsuRootFilterComponent? = null

    /**
     * Called by the tab manager when this window's name changes (via the configure dialog) so the
     * tab title in the Changes tool window can be updated.
     */
    var onTitleChanged: ((String) -> Unit)? = null

    init {
        // Wire per-window column-width storage so resizes are persisted to config, not global settings
        logTable.columnWidthsStorage = config.columnWidths
        logTable.onColumnWidthsSaved = { persistConfig() }

        // Load saved column widths from the per-window config
        logTable.loadColumnWidths()

        // Restore search/filter options from config
        useRegex = config.useRegex
        matchCase = config.matchCase
        matchWholeWords = config.matchWholeWords
        if (config.searchText.isNotEmpty()) searchTextField.text = config.searchText

        // Restore filter component state (reference is restored later in onDataLoaded via retryFilter)
        if (config.selectedReference.isNotEmpty()) {
            referenceFilterComponent.setInitialReference(config.selectedReference)
        }
        if (config.authorFilter.isNotEmpty()) authorFilterComponent.setSelectedAuthors(config.authorFilter)
        if (config.dateFilterPeriodName.isNotEmpty()) {
            dateFilterComponent.setSelectedPeriod(config.dateFilterPeriodName)
        }

        // Persist filter changes into config
        referenceFilterComponent.addChangeListener {
            config.selectedReference = referenceFilterComponent.getSelectedReferenceName()
            persistConfig()
        }
        authorFilterComponent.addChangeListener {
            config.authorFilter = authorFilterComponent.getSelectedAuthors().toMutableList()
            persistConfig()
        }
        dateFilterComponent.addChangeListener {
            config.dateFilterPeriodName = dateFilterComponent.getSelectedPeriodName()
            persistConfig()
        }

        // Subscribe to state changes from all repositories
        setupStateListener()

        // Listen for change selection requests (data reload is handled by logRefresh listener)
        project.stateModel.changeSelection.connect(this) { key ->
            logTable.requestSelection(key)
        }

        logTable.onSelectionExpansionNeeded = { key ->
            val rev = key.revision
            if (rev is ChangeId) (dataLoader as UnifiedJujutsuLogDataLoader).loadExpanding(key.repo, rev)
        }

        referenceFilterComponent.onReferenceExpansionNeeded = { referenceName ->
            project.stateModel.references.value.forEach { (repo, references) ->
                val changeId =
                    references.bookmarks.firstOrNull { it.bookmark.name.name == referenceName }?.id
                        ?: references.tags.firstOrNull { it.tag.name == referenceName }?.id
                changeId?.let { (dataLoader as UnifiedJujutsuLogDataLoader).loadExpanding(repo, it) }
            }
        }

        log.info("UnifiedJujutsuLogPanel initialized for project: ${project.name}, window: ${config.name}")
    }

    private fun setupStateListener() {
        project.stateModel.logRefresh.connect(this) { _ -> refresh() }
    }

    override fun onDataLoaded(newData: UnifiedJujutsuLogDataLoader.Data) {
        logTable.setEntries(newData.entries)
        logTable.updateGraph(newData.graphNodes)
        updateRootFilterVisibility()
        updateStatusBar(newData.entries.size, newData.limit)
        detailsPanel.showCommits(logTable.selectedEntries)
        referenceFilterComponent.retryFilter()
        // Restore root filter after data loads (roots are only available once the model is populated)
        if (config.selectedRootPaths.isNotEmpty()) {
            rootFilterComponent?.setSelectedRoots(config.selectedRootPaths.toSet())
        }
    }

    /**
     * Persists all UI state into [config] and calls [JujutsuSettings.upsertLogWindow].
     * Called from [onConfigChanged] and from individual filter-change listeners.
     */
    override fun onConfigChanged() {
        columnManager.saveTo(config)
        config.detailsOnRight = detailsOnRight
        config.useRegex = useRegex
        config.matchCase = matchCase
        config.matchWholeWords = matchWholeWords
        config.searchText = searchTextField.text
        persistConfig()
    }

    private fun persistConfig() {
        JujutsuSettings.getInstance(project).upsertLogWindow(config)
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
            // Persist root selection changes
            addChangeListener {
                config.selectedRootPaths = getSelectedRootPaths().toMutableList()
                persistConfig()
            }
        }
        filterPanel.add(rootFilterComponent)
        filterPanel.add(Box.createHorizontalStrut(5))

        // Bookmark widget
        val widget = JujutsuBookmarkWidget(project)
        Disposer.register(this, widget)
        filterPanel.add(widget)
        filterPanel.add(Box.createHorizontalStrut(5))
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
        // detailsPanel is registered as a child via Disposer — no manual dispose needed
    }
}
