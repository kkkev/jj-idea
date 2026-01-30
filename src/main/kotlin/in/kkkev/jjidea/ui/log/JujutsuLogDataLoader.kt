package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry

/**
 * Loads commit log data in the background and updates the table model on EDT.
 *
 * Uses our existing JujutsuLogProvider infrastructure - no dependency on
 * IntelliJ's VcsLogData.
 */
class JujutsuLogDataLoader(
    private val repo: JujutsuRepository,
    private val tableModel: JujutsuLogTableModel,
    private val table: JujutsuLogTable,
    private val onDataLoaded: (() -> Unit)? = null
) {
    private val log = Logger.getInstance(javaClass)
    private val graphBuilder = CommitGraphBuilder()

    /**
     * Flag to track if working copy selection is pending.
     * Set when a selection is requested before data is loaded.
     */
    @Volatile
    var pendingSelectWorkingCopy = false

    /**
     * Load commits in the background.
     *
     * @param revset Revision expression to load (default: all commits)
     */
    fun loadCommits(revset: Expression = Expression.ALL) {
        object : Task.Backgroundable(repo.project, "Loading Jujutsu Commits", true) {
            private var entries: List<LogEntry> = emptyList()
            private var error: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Loading commits from Jujutsu..."
                indicator.isIndeterminate = false

                try {
                    // Load log entries using our existing LogService
                    val result = repo.logService.getLog(revset)

                    result.onSuccess { loadedEntries ->
                        entries = loadedEntries
                        log.info("Loaded ${entries.size} commits")
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
                if (error == null) {
                    // Build graph layout with full branching/merging support
                    val graphNodes = graphBuilder.buildGraph(entries)

                    // Update table model and graph on EDT
                    ApplicationManager.getApplication().invokeLater {
                        tableModel.setEntries(entries)
                        table.updateGraph(graphNodes)
                        log.info("Table updated with ${entries.size} commits and graph layout")

                        // Handle pending working copy selection
                        if (pendingSelectWorkingCopy) {
                            pendingSelectWorkingCopy = false
                            selectWorkingCopyInTable()
                        }

                        // Notify callback
                        onDataLoaded?.invoke()
                    }
                }
            }

            override fun onThrowable(throwable: Throwable) {
                log.error("Background task failed", throwable)
            }
        }.queue()
    }

    /**
     * Select the working copy (@) entry in the table and scroll it into view.
     */
    fun selectWorkingCopyInTable() {
        // Find the working copy entry in the table model
        val workingCopyIndex = (0 until tableModel.rowCount).firstOrNull { row ->
            tableModel.getEntry(row)?.isWorkingCopy == true
        }

        if (workingCopyIndex != null) {
            // Select the row
            table.setRowSelectionInterval(workingCopyIndex, workingCopyIndex)

            // Scroll to the selected row
            table.scrollRectToVisible(table.getCellRect(workingCopyIndex, 0, true))

            log.info("Selected working copy at row $workingCopyIndex")
        } else {
            log.warn("Working copy not found in table after refresh")
        }
    }

    /**
     * Refresh the log - reload all commits.
     */
    fun refresh() {
        log.info("Refreshing log")
        loadCommits()
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
