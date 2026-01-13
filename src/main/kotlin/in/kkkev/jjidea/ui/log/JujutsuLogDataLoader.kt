package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.jujutsuVcs

/**
 * Loads commit log data in the background and updates the table model on EDT.
 *
 * Uses our existing JujutsuLogProvider infrastructure - no dependency on
 * IntelliJ's VcsLogData.
 */
class JujutsuLogDataLoader(
    private val project: Project,
    private val root: VirtualFile,
    private val tableModel: JujutsuLogTableModel,
    private val table: JujutsuLogTable
) {
    private val log = Logger.getInstance(javaClass)
    private val graphBuilder = CommitGraphBuilder()

    /**
     * Load commits in the background.
     *
     * @param revset Revision expression to load (default: all commits)
     * @param selectWorkingCopy If true, select the working copy (@) after load completes
     */
    fun loadCommits(revset: Expression = Expression.ALL, selectWorkingCopy: Boolean = false) {
        object : Task.Backgroundable(project, "Loading Jujutsu Commits", true) {
            private var entries: List<LogEntry> = emptyList()
            private var error: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Loading commits from Jujutsu..."
                indicator.isIndeterminate = false

                try {
                    // Load log entries using our existing LogService
                    val result = root.jujutsuVcs.logService.getLog(revset)

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

                        // Select working copy if requested
                        if (selectWorkingCopy) {
                            selectWorkingCopyInTable()
                        }
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
    private fun selectWorkingCopyInTable() {
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
     *
     * @param selectWorkingCopy If true, select the working copy (@) after refresh completes
     */
    fun refresh(selectWorkingCopy: Boolean = false) {
        log.info("Refreshing log (selectWorkingCopy=$selectWorkingCopy)")
        loadCommits(selectWorkingCopy = selectWorkingCopy)
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
