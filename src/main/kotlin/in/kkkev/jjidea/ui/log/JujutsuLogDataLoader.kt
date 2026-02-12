package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import `in`.kkkev.jjidea.jj.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads commit log data in the background and updates the table model on EDT.
 *
 * Uses our existing JujutsuLogProvider infrastructure - no dependency on
 * IntelliJ's VcsLogData.
 *
 * Subscribes to the state model's changeSelection notifier to handle selection
 * requests for this repository.
 */
class JujutsuLogDataLoader(
    private val repo: JujutsuRepository,
    private val tableModel: JujutsuLogTableModel,
    private val table: JujutsuLogTable,
    parentDisposable: Disposable,
    private val onDataLoaded: (() -> Unit)? = null
) {
    private val log = Logger.getInstance(javaClass)
    private val graphBuilder = CommitGraphBuilder()

    private val loading = AtomicBoolean(false)
    private val pendingRefresh = AtomicBoolean(false)
    private val currentIndicator = AtomicReference<ProgressIndicator?>(null)

    /**
     * Pending selection to apply after data loads.
     */
    @Volatile
    private var pendingSelection: Revision? = null

    @Volatile
    private var hasPendingSelection = false

    init {
        // Listen for change selection requests for this repository
        repo.project.stateModel.changeSelection.connect(parentDisposable) { key ->
            if (key.repo == repo) {
                requestSelection(key.revision)
            }
        }
    }

    /**
     * Load commits in the background.
     *
     * @param revset Revision expression to load (default: all commits)
     * @param onLoaded Optional callback invoked after data is loaded (on EDT)
     */
    fun loadCommits(revset: Expression = Expression.ALL, onLoaded: (() -> Unit)? = null) {
        if (!loading.compareAndSet(false, true)) {
            // A load is already in progress; mark pending so we reload when it finishes
            pendingRefresh.set(true)
            return
        }

        // Cancel any previous load that hasn't started its EDT callback yet
        currentIndicator.get()?.cancel()

        object : Task.Backgroundable(repo.project, "Loading Jujutsu Commits", true) {
            private var entries: List<LogEntry> = emptyList()
            private var graphNodes: Map<ChangeId, GraphNode> = emptyMap()
            private var error: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                currentIndicator.set(indicator)
                indicator.text = "Loading commits from Jujutsu..."
                indicator.isIndeterminate = false

                try {
                    val result = repo.logService.getLog(revset)

                    result.onSuccess { loadedEntries ->
                        entries = loadedEntries
                        log.info("Loaded ${entries.size} commits")
                        graphNodes = graphBuilder.buildGraph(entries)
                    }.onFailure { e ->
                        error = e
                        log.error("Failed to load commits", e)
                    }
                } catch (e: Exception) {
                    error = e
                    log.error("Exception loading commits", e)
                }
            }

            override fun onSuccess() {
                loading.set(false)
                currentIndicator.set(null)

                if (error == null) {
                    ApplicationManager.getApplication().invokeLater {
                        tableModel.setEntries(entries)
                        table.updateGraph(graphNodes)
                        log.info("Table updated with ${entries.size} commits and graph layout")

                        onDataLoaded?.invoke()
                        onLoaded?.invoke()

                        if (hasPendingSelection) {
                            hasPendingSelection = false
                            pendingSelection?.let { selectEntry(it) }
                            pendingSelection = null
                        }
                    }
                }

                // If a refresh was requested while we were loading, re-run
                if (pendingRefresh.compareAndSet(true, false)) {
                    loadCommits(revset)
                }
            }

            override fun onThrowable(throwable: Throwable) {
                loading.set(false)
                currentIndicator.set(null)
                log.error("Background task failed", throwable)

                if (pendingRefresh.compareAndSet(true, false)) {
                    loadCommits(revset)
                }
            }
        }.queue()
    }

    /**
     * Select an entry in the table by revision and scroll it into view.
     *
     * @param revision The revision to select. Supports:
     *   - [ChangeId] to select a specific commit
     *   - [WorkingCopy] to select the working copy (@)
     *   - Other [Revision] types for future bookmark/tag support
     */
    fun selectEntry(revision: Revision) {
        val rowIndex = when (revision) {
            is ChangeId -> (0 until tableModel.rowCount).firstOrNull { row ->
                tableModel.getEntry(row)?.id == revision
            }

            WorkingCopy -> (0 until tableModel.rowCount).firstOrNull { row ->
                tableModel.getEntry(row)?.isWorkingCopy == true
            }

            else -> {
                log.warn("Unsupported revision type for selection: $revision")
                null
            }
        }

        if (rowIndex != null) {
            table.setRowSelectionInterval(rowIndex, rowIndex)
            table.scrollRectToVisible(table.getCellRect(rowIndex, 0, true))
            log.info("Selected entry at row $rowIndex ($revision)")
        } else {
            log.warn("Entry not found: $revision")
        }
    }

    /**
     * Request selection of an entry and trigger a refresh.
     * VCS operations (abandon, edit, new) fire changeSelection to request a specific entry
     * be selected after the operation. We must always refresh when this happens because the
     * log data has changed, even if repositoryStates reports no WC change.
     */
    private fun requestSelection(revision: Revision) {
        pendingSelection = revision
        hasPendingSelection = true
        loadCommits()
    }

    /**
     * Refresh the log - reload all commits, preserving current selection.
     * Does not override explicit selection requests from changeSelection.
     */
    fun refresh() {
        log.info("Refreshing log")
        // Save current selection to restore after refresh (unless an explicit selection is pending)
        val savedSelection = if (!hasPendingSelection) table.selectedEntry?.id else null

        loadCommits(onLoaded = {
            // Only restore if no explicit selection has been requested
            if (savedSelection != null && !hasPendingSelection) {
                selectEntry(savedSelection)
            }
        })
    }

    /**
     * Load more commits (for pagination).
     * Not implemented yet - Phase 1 loads all commits up to limit.
     */
    fun loadMore() {
        // TODO: Implement pagination
        log.info("Load more not yet implemented")
    }
}
