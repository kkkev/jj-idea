package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.settings.LogWindowConfig
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories

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
     * Builds the graph layout for the visible (filtered) subset of entries.
     * Runs on the EDT; the input list is already topo-sorted by the data loader.
     */
    private val graphBuilder = CommitGraphBuilder()

    /**
     * The full-set graph nodes produced by the background data loader.
     * Reused unchanged when no filter is active (visible set == full set).
     */
    private var fullGraphNodes: Map<ChangeKey, GraphNode> = emptyMap()

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

        // Restore + persist root gutter expansion state (per-tab, see LogWindowConfig.rootGutterExpanded)
        logTable.isRootGutterExpanded = config.rootGutterExpanded
        logTable.onGutterExpansionChanged = {
            config.rootGutterExpanded = logTable.isRootGutterExpanded
            persistConfig()
        }

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
            // onMissing (GitHub #76): the selected change was abandoned/rewritten since it was
            // rendered - drop the stale pending selection rather than retrying it on every refresh.
            if (rev is ChangeId) {
                (dataLoader as UnifiedJujutsuLogDataLoader)
                    .loadExpanding(key.repo, rev, onMissing = { logTable.clearNavigation() })
            }
        }

        referenceFilterComponent.onReferenceExpansionNeeded = { referenceName ->
            project.stateModel.references.value.forEach { (repo, references) ->
                val changeId =
                    references.bookmarks.firstOrNull { it.bookmark.name.name == referenceName }?.id
                        ?: references.tags.firstOrNull { it.tag.name == referenceName }?.id
                changeId?.let {
                    (dataLoader as UnifiedJujutsuLogDataLoader)
                        .loadExpanding(repo, it, onMissing = { logTable.clearNavigation() })
                }
            }
        }

        // Rebuild the graph layout synchronously before fireTableDataChanged() so the
        // renderer is correct when Swing first paints the filtered rows (no intermediate flash).
        logTable.logModel.onFilterApplied = { refreshDisplayedGraph() }

        log.info("UnifiedJujutsuLogPanel initialized for project: ${project.name}, window: ${config.name}")
    }

    private fun setupStateListener() {
        project.stateModel.logRefresh.connect(this) { _ -> refresh() }
    }

    /**
     * Rebuilds the graph layout for the currently-visible (filtered) entries and installs it.
     *
     * When no filter is active the visible set equals the full set, so we reuse [fullGraphNodes]
     * (built off-EDT by the data loader) without any extra work.
     * When a filter is active the visible set is a topo-ordered subset, and we rebuild from it
     * on the EDT. At the default log limit of 500 entries this is fast enough to be imperceptible.
     */
    private fun refreshDisplayedGraph() {
        val model = logTable.logModel
        val filtered = model.getFilteredEntries()
        val graph =
            if (filtered.size == model.getAllEntries().size) {
                fullGraphNodes
            } else {
                graphBuilder.buildGraph(filtered)
            }
        logTable.updateGraph(graph)
    }

    override fun onDataLoaded(newData: UnifiedJujutsuLogDataLoader.Data) {
        // Store the full-set graph before setEntries() so refreshDisplayedGraph() can reuse it
        // immediately when no filter is active.
        fullGraphNodes = newData.graphNodes
        logTable.setEntries(newData.entries)
        // Rebuild immediately (no debounce) to handle any filter that was restored from config
        // before the first load, and to replace any graph from a previous load.
        refreshDisplayedGraph()
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
     * Runs a whole-repo search (jj-idea-lpbv) when Enter is pressed with non-blank search text,
     * so a pasted Git hash or a match in an older commit's description/author is found even when
     * it's outside the loaded log window. Results merge into the table (see
     * [UnifiedJujutsuLogDataLoader.searchWholeRepo]); the outcome replaces whatever the status bar
     * was showing (e.g. the truncation notice set by [onDataLoaded] as part of the same reload).
     */
    override fun onSearchSubmitted(text: String) {
        (dataLoader as UnifiedJujutsuLogDataLoader).searchWholeRepo(
            text,
            useRegex,
            matchCase,
            matchWholeWords
        ) { found ->
            if (found > 0) {
                showStatusMessage(JujutsuBundle.message("log.status.search.found", found, text))
            } else {
                showStatusMessage(JujutsuBundle.message("log.status.search.none", text))
            }
        }
    }

    /**
     * One-click New/Edit/Rebase/Describe buttons ahead of Refresh, for the most-used log
     * operations (GitHub #51 point 8 / VisualJJ parity; Rebase and Describe added for GitHub #78,
     * the requester's most-used commands, previously only reachable via the right-click menu).
     * File history doesn't override this.
     */
    override fun primaryActions() = listOfNotNull(
        ActionManager.getInstance().getAction("Jujutsu.NewChange"),
        ActionManager.getInstance().getAction("Jujutsu.EditChange"),
        ActionManager.getInstance().getAction("Jujutsu.RebaseChangeToolbar"),
        ActionManager.getInstance().getAction("Jujutsu.DescribeChangeToolbar")
    )

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

    override fun createOtherFilterComponents(): List<JujutsuFilterComponent> {
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

        return listOfNotNull(rootFilterComponent)
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
