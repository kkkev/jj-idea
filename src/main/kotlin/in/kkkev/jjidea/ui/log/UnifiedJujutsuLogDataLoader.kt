package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Loads commit log data from multiple repositories in parallel and updates the table model on EDT.
 *
 * Unified loader that:
 * - Loads commits from all provided repositories concurrently
 * - Merges results into a single list sorted by timestamp (newest first)
 * - Updates the table model and graph on EDT
 */
class UnifiedJujutsuLogDataLoader(
    private val project: Project,
    private val repositories: () -> List<JujutsuRepository>,
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
     * Load commits from all repositories in the background.
     *
     * @param revset Revision expression to load (default: all commits)
     */
    fun loadCommits(revset: Expression = Expression.ALL) {
        val repos = repositories()
        if (repos.isEmpty()) {
            log.info("No repositories to load commits from")
            ApplicationManager.getApplication().invokeLater {
                tableModel.setEntries(emptyList())
                table.updateGraph(emptyMap())
            }
            return
        }

        object : Task.Backgroundable(project, "Loading Jujutsu Commits", true) {
            private val entriesByRepo = ConcurrentHashMap<JujutsuRepository, List<LogEntry>>()
            private val errors = ConcurrentHashMap<JujutsuRepository, Throwable>()

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Loading commits from ${repos.size} repositories..."
                indicator.isIndeterminate = false

                val latch = CountDownLatch(repos.size)
                val totalRepos = repos.size

                repos.forEachIndexed { index, repo ->
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            indicator.text2 = "Loading from ${repo.relativePath.ifEmpty { "root" }}..."
                            indicator.fraction = index.toDouble() / totalRepos

                            val result = repo.logService.getLog(revset)

                            result.onSuccess { loadedEntries ->
                                entriesByRepo[repo] = loadedEntries
                                log.info(
                                    "Loaded ${loadedEntries.size} commits from ${repo.relativePath.ifEmpty {
                                        "root"
                                    }}"
                                )
                            }.onFailure { e ->
                                errors[repo] = e
                                log.error("Failed to load commits from ${repo.relativePath}", e)
                            }
                        } catch (e: Exception) {
                            errors[repo] = e
                            log.error("Exception loading commits from ${repo.relativePath}", e)
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                // Wait for all repositories to finish loading
                latch.await(5, TimeUnit.MINUTES)
            }

            override fun onSuccess() {
                if (entriesByRepo.isEmpty() && errors.isNotEmpty()) {
                    log.error("All repositories failed to load")
                    return
                }

                // Merge all entries and sort by timestamp (newest first)
                val allEntries = entriesByRepo.values.flatten()
                    .sortedByDescending { it.authorTimestamp ?: it.committerTimestamp }

                log.info("Merged ${allEntries.size} commits from ${entriesByRepo.size} repositories")

                // Build graph layout with full branching/merging support
                val graphNodes = graphBuilder.buildGraph(allEntries)

                // Update table model and graph on EDT
                ApplicationManager.getApplication().invokeLater {
                    tableModel.setEntries(allEntries)
                    table.updateGraph(graphNodes)
                    log.info("Table updated with ${allEntries.size} commits and graph layout")

                    // Handle pending working copy selection
                    if (pendingSelectWorkingCopy) {
                        pendingSelectWorkingCopy = false
                        selectWorkingCopyInTable()
                    }

                    // Notify callback
                    onDataLoaded?.invoke()
                }
            }

            override fun onThrowable(throwable: Throwable) {
                log.error("Background task failed", throwable)
            }
        }.queue()
    }

    /**
     * Select the working copy (@) entry in the table and scroll it into view.
     * For multi-root, selects the first working copy found.
     */
    fun selectWorkingCopyInTable() {
        // Find the first working copy entry in the table model
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
     * Refresh the log - reload all commits from all repositories.
     */
    fun refresh() {
        log.info("Refreshing unified log")
        loadCommits()
    }
}
