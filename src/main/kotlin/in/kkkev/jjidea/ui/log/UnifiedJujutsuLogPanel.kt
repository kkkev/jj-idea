package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.Alarm
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.ManagedActions
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.settings.LogWindowConfig
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.ui.log.bookmarks.BookmarksStripeButton
import `in`.kkkev.jjidea.ui.log.bookmarks.JujutsuBookmarksPanel
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
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
        revsetSearch = true,
        configureColumnManager = { it.loadFrom(config) }
    ) {
    private val log = Logger.getInstance(javaClass)

    // Root filter component (created lazily, only shown if multiple roots)
    private var rootFilterComponent: JujutsuRootFilterComponent? = null

    // The Scope chip (jj-idea-vqpn, GitHub #116; jj-idea-ehwm.4) - created in
    // createScopeComponent(), which the base class constructor calls before this
    // subclass's own init{} block runs.

    private var revsetFilterComponent: JujutsuRevsetFilterComponent? = null

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

    // Bookmarks panel (jj-idea-b2ae / GitHub #48): hosted in an outer splitter to the left of the
    // table+details area, git4idea-Branches-dashboard style. Visibility is per-window
    // (config.bookmarksPanelVisible, default expanded — matches rootGutterExpanded's default);
    // the splitter's own width is a global PropertiesComponent key, matching git4idea's single
    // shared proportion key for its own splitter. bookmarksStripe stays visible even when
    // collapsed, so there's always something on screen to click back open.
    private val bookmarksPanel = JujutsuBookmarksPanel(
        project,
        config.bookmarkNodeExpanded,
        entryLookup = { key -> logTable.logModel.entryFor(key) },
        allEntries = { logTable.logModel.getFilteredEntries() },
        onExpansionChanged = { persistConfig() }
    )
    private val bookmarksStripe = BookmarksStripeButton { setBookmarksPanelVisible(!config.bookmarksPanelVisible) }
    private val bookmarksSplitter: OnePixelSplitter

    /**
     * Called by the tab manager when this window's name changes (via the configure dialog) so the
     * tab title in the Changes tool window can be updated.
     */
    var onTitleChanged: ((String) -> Unit)? = null

    // jj-idea-ehwm.3: every filter except Root is resolved by jj, which the loader runs after this delay
    // for search text (chips apply at once). Nothing applies before the first load - see onDataLoaded.
    private val filterAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var initialFilterApplied = false

    init {
        logTable.logModel.revsetDriven = true
        logTable.logModel.onFilterStateChanged = { immediate -> scheduleFilterApply(immediate) }
        Disposer.register(this, bookmarksPanel)
        bookmarksStripe.setExpanded(config.bookmarksPanelVisible)
        bookmarksSplitter = installLeftComponent(
            bookmarksPanel,
            bookmarksStripe,
            "jujutsu.bookmarks.panel.proportion",
            config.bookmarksPanelVisible
        )

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
        revsetMode = config.searchRevsetMode
        if (config.searchText.isNotEmpty()) searchField.text = config.searchText

        // Restore filter component state (reference is restored later in onDataLoaded via retryFilter)
        if (config.selectedReference.isNotEmpty()) {
            referenceFilterComponent.setInitialReference(config.selectedReference)
        }
        if (config.authorFilter.isNotEmpty()) authorFilterComponent.setSelectedAuthors(config.authorFilter)
        if (config.dateFilterPeriodName.isNotEmpty()) {
            dateFilterComponent.setSelectedPeriod(config.dateFilterPeriodName)
        }
        if (config.revsetFilter.isNotEmpty()) {
            revsetFilterComponent?.setInitialRevset(config.revsetFilter)
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
        revsetFilterComponent?.addChangeListener {
            config.revsetFilter = revsetFilterComponent?.getRevsetText().orEmpty()
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
                (dataLoader as UnifiedJujutsuLogDataLoader).loadExpanding(key.repo, rev, onMissing = {
                    logTable.clearNavigation()
                    log.info("Navigation target $key not found; it may have been abandoned or rewritten")
                    JujutsuNotifications.notifyNavigationTargetMissing(project, rev)
                })
            }
        }

        // jj-idea-7rxm (GitHub #140): a loaded target hidden by a filter used to fail silently.
        // Reset Filters clears the search and chips but keeps the tab's scope, which is its definition.
        // If the target is still hidden afterwards only the scope can be responsible, and the user
        // gets a separate, explicit "Clear Scope" choice.
        logTable.onSelectionHiddenByFilter = { key ->
            val rev = key.revision
            if (filterApplyPending) {
                deferredHiddenKey = key
            } else if (rev is ChangeId) {
                val scope = revsetFilterComponent?.getRevsetText().orEmpty()
                if (hasNarrowingFilters() || scope.isEmpty()) {
                    JujutsuNotifications.notifyNavigationTargetFiltered(project, rev) {
                        resetAllFilters()
                        logTable.requestSelection(key)
                    }
                } else {
                    JujutsuNotifications.notifyNavigationTargetOutOfScope(project, rev, scope) {
                        resetAllFilters(includeScope = true)
                        logTable.requestSelection(key)
                    }
                }
            }
        }

        // Rebuild the graph layout synchronously before fireTableDataChanged() so the
        // renderer is correct when Swing first paints the filtered rows (no intermediate flash).
        logTable.logModel.onFilterApplied = { refreshDisplayedGraph() }

        log.info("UnifiedJujutsuLogPanel initialized for project: ${project.name}, window: ${config.name}")
    }

    private fun setupStateListener() {
        project.stateModel.logRefresh.connect(this) { _ ->
            logTable.markRefreshPending()
            // The loader's refresh also re-fetches the active filter's matches (jj-idea-ehwm.3).
            refresh()
        }
        // jj-idea-dii4 (GitHub #128): rows and references reload in parallel, so a row's bookmark
        // ahead/behind can be merged against the previous references - re-merge once they land.
        project.stateModel.references.connect(this) { _ ->
            (dataLoader as UnifiedJujutsuLogDataLoader).reapplyBookmarkCorrections()
        }
    }

    /** Runs [applyFilterNow] - at once, or after a short pause for search text so typing doesn't spawn a jj call per key. */
    // True from scheduling a filter apply until jj's answer has been published. The rows on screen are
    // stale meanwhile, so "this change is hidden" can't be trusted: the pending selection is retried
    // when the fresh rows land (jj-idea-ehwm.4).
    private var filterApplyPending = false

    // A hidden-selection report that arrived while [filterApplyPending]; re-requested once the answer is in.
    private var deferredHiddenKey: ChangeKey? = null

    private fun scheduleFilterApply(immediate: Boolean) {
        if (!initialFilterApplied) return
        filterApplyPending = true
        filterAlarm.cancelAllRequests()
        showStatusMessage(JujutsuBundle.message("log.status.filtering"))
        filterAlarm.addRequest(::applyFilterNow, if (immediate) 0 else FILTER_DEBOUNCE_MS)
    }

    /** Has jj resolve the model's current filters; the matches arrive with the next [onDataLoaded]. */
    private fun applyFilterNow() {
        if (Disposer.isDisposed(this) || project.isDisposed) return
        (dataLoader as UnifiedJujutsuLogDataLoader).applyFilter(logTable.logModel.filterState()) { errors ->
            filterApplyPending = false
            deferredHiddenKey?.let {
                deferredHiddenKey = null
                logTable.requestSelection(it)
            }
            if (errors.isEmpty()) {
                searchField.setError(null)
                clearStatusMessage()
            } else if (revsetMode && searchField.text.isNotBlank()) {
                attributeRevsetError(errors)
            } else {
                searchField.setError(null)
                showRevsetError(errors.values)
            }
        }
    }

    private fun showRevsetError(messages: Collection<String>) = showStatusMessage(
        JujutsuBundle.message(
            "log.status.revset.error",
            messages.map(::conciseRevsetError).distinct().joinToString("; ")
        )
    )

    // Bumped per attribution so a slow validation of old text can't overwrite a newer result.
    private var searchErrorGeneration = 0

    /**
     * The filter failed while the search box holds a revset, but jj's message is about the compiled
     * revset - scope, chips and search together - so it can't say which part is wrong. Checks the
     * search text on its own: if that fails it is the culprit and the box shows jj's (shortened) message
     * for just that text; if it passes, the fault is elsewhere (the scope) and the box stays clean.
     */
    private fun attributeRevsetError(errors: Map<JujutsuRepository, String>) {
        val generation = ++searchErrorGeneration
        val text = searchField.text.trim()
        val repos = config.selectedRepos(project.initialisedJujutsuRepositories)
        runInBackground {
            val own = validateRevsetFilter(repos, text) { Revset.Default }
            runLater {
                if (generation != searchErrorGeneration || Disposer.isDisposed(this)) return@runLater
                if (own.isEmpty()) {
                    searchField.setError(null)
                    showRevsetError(errors.values)
                } else {
                    val message = own.values.map(::conciseRevsetError).distinct().joinToString("; ")
                    searchField.setError(message)
                    showStatusMessage(JujutsuBundle.message("log.status.revset.search.error", message))
                }
            }
        }
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
                graphBuilder.buildGraph(filtered, model.getAllEntries())
            }
        logTable.updateGraph(graph)
    }

    // jj-idea-2570.8: repos whose background emptiness fetches (immutable merges) we repaint for.
    private val emptinessWatched = HashSet<JujutsuRepository>()

    private fun watchMergeEmptiness(entries: List<LogEntry>) {
        for (repo in entries.mapTo(LinkedHashSet()) { it.repo }) {
            if (!emptinessWatched.add(repo)) continue
            repo.mergeEmptiness.addListener(this) { landed ->
                ApplicationManager.getApplication().invokeLater({
                    // Cells are rebuilt on every paint, so a repaint is all the rows need - no re-apply.
                    logTable.repaint()
                    val selected = logTable.selectedEntries
                    if (selected.any { it.commitId in landed }) detailsPanel.showCommits(selected)
                }, { project.isDisposed })
            }
        }
    }

    override fun onDataLoaded(newData: UnifiedJujutsuLogDataLoader.Data) {
        // A background load can land after the project/panel started closing (jj-idea-mrcv).
        if (Disposer.isDisposed(this) || project.isDisposed) return
        watchMergeEmptiness(newData.entries)
        // Store the full-set graph before setEntries() so refreshDisplayedGraph() can reuse it
        // immediately when no filter is active.
        fullGraphNodes = newData.graphNodes
        logTable.setEntries(newData.entries, newData.filterMatches)
        // Rebuild immediately (no debounce) to handle any filter that was restored from config
        // before the first load, and to replace any graph from a previous load.
        refreshDisplayedGraph()
        updateRootFilterVisibility()
        updateStatusBar(newData.entries.size, newData.limit, newData.paged)
        detailsPanel.showCommits(logTable.selectedEntries)
        if (!initialFilterApplied) {
            // Filters restored from config only reach jj now that there is a base log to merge into.
            initialFilterApplied = true
            referenceFilterComponent.retryFilter()
            if (logTable.logModel.filterState().isActive) scheduleFilterApply(immediate = true)
        }
        // Restore root filter after data loads (roots are only available once the model is populated)
        if (config.selectedRootPaths.isNotEmpty() || config.excludedRootPaths.isNotEmpty()) {
            rootFilterComponent?.setSelectedRoots(config.selectedRootPaths.toSet(), config.excludedRootPaths.toSet())
        }
    }

    /**
     * Enter in the search field applies the filter without waiting out the typing pause. (The search
     * itself already covers the whole repository - it is part of the revset jj resolves, jj-idea-ehwm.3.)
     */
    override fun onSearchSubmitted(text: String) = scheduleFilterApply(immediate = true)

    /**
     * One-click New/Edit/Rebase/Describe buttons ahead of Refresh, for the most-used log
     * operations (GitHub #51 point 8 / VisualJJ parity; Rebase and Describe added for GitHub #78,
     * the requester's most-used commands, previously only reachable via the right-click menu).
     * File history doesn't override this.
     */
    override fun primaryActions() = listOfNotNull(
        ManagedActions["Jujutsu.NewChange"],
        ManagedActions["Jujutsu.EditChange"],
        ManagedActions["Jujutsu.RebaseChangeToolbar"],
        ManagedActions["Jujutsu.DescribeChangeToolbar"]
    )

    /**
     * Adds a "Show Bookmarks Panel" toggle (jj-idea-b2ae) to the shared View Options popup,
     * alongside the other per-table display toggles — rather than a dedicated toolbar button,
     * since [bookmarksStripe] already gives it an always-visible click target.
     */
    override fun createViewOptionsActionGroup() = super.createViewOptionsActionGroup().apply {
        addSeparator()
        addAction(ToggleBookmarksPanelAction())
    }

    /** Single place both [ToggleBookmarksPanelAction] and [bookmarksStripe] toggle through. */
    private fun setBookmarksPanelVisible(visible: Boolean) {
        config.bookmarksPanelVisible = visible
        bookmarksSplitter.firstComponent = if (visible) bookmarksPanel else null
        bookmarksStripe.setExpanded(visible)
        persistConfig()
    }

    /** Shows/hides [bookmarksPanel] (jj-idea-b2ae) from the View Options popup. */
    private inner class ToggleBookmarksPanelAction : ToggleAction(JujutsuBundle.message("bookmarks.panel.viewoption")) {
        override fun isSelected(e: AnActionEvent) = config.bookmarksPanelVisible

        override fun setSelected(e: AnActionEvent, state: Boolean) = setBookmarksPanelVisible(state)
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
        config.searchRevsetMode = revsetMode
        config.searchText = searchField.text
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
        rootFilterComponent = JujutsuRootFilterComponent { logTable.logModel.getAllRoots() }.apply {
            initUi()
            initialize { logTable.logModel.setRootFilter(it.included, it.excluded) }
            // Initially hidden, will show when data loads if multiple roots
            isVisible = false
            // Persist root selection changes
            addChangeListener {
                config.selectedRootPaths = getSelectedRootPaths().toMutableList()
                config.excludedRootPaths = getExcludedRootPaths().toMutableList()
                persistConfig()
            }
        }

        return listOfNotNull(rootFilterComponent)
    }

    /**
     * The tab's Scope chip (jj-idea-vqpn, GitHub #116; jj-idea-ehwm.4): a base revset the search box
     * and the other chips narrow within. Log-only (file history doesn't override this), and reads
     * the same per-repo resolved revset the data loader itself fetches with, so the scope can never
     * surface a commit outside what's already loadable for this tab.
     */
    override fun createScopeComponent(): JujutsuFilterComponent {
        val component = JujutsuRevsetFilterComponent(
            repos = { config.selectedRepos(project.initialisedJujutsuRepositories) },
            baseFor = { repo -> JujutsuSettings.getInstance(project).resolvedLogRevset(repo) },
            tableModel = logTable.logModel
        ).apply {
            initUi()
            initialize()
        }
        revsetFilterComponent = component
        return component
    }

    override fun onSaveRevsetAliasRequested(text: String) {
        val repos = config.selectedRepos(project.initialisedJujutsuRepositories)
        if (repos.isEmpty()) return
        // The box switches to the alias name, which re-filters and persists like any other edit.
        SaveRevsetAliasDialog(
            repos,
            text,
            baseFor = { repo -> JujutsuSettings.getInstance(project).resolvedLogRevset(repo) },
            onSaved = { name -> searchField.text = name }
        ).show()
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
        dataLoader.close() // stops the paged log's idle trickle (jj-idea-2570.5)
        // detailsPanel is registered as a child via Disposer — no manual dispose needed
    }
}

private const val FILTER_DEBOUNCE_MS = 300
