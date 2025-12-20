package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.JujutsuVcs

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
    private val log = Logger.getInstance(JujutsuLogDataLoader::class.java)
    private val graphBuilder = CommitGraphBuilder()

    /**
     * Load commits in the background.
     *
     * @param revset Revision expression to load (default: all commits)
     */
    fun loadCommits(revset: Expression = Expression.ALL) {
        object : Task.Backgroundable(project, "Loading Jujutsu Commits", true) {
            private var entries: List<LogEntry> = emptyList()
            private var error: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Loading commits from Jujutsu..."
                indicator.isIndeterminate = false

                try {
                    // Get VCS instance for this root
                    val vcs = JujutsuVcs.findRequired(root)

                    // Load log entries using our existing LogService
                    val result = vcs.logService.getLog(revset)

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
                    }
                }
            }

            override fun onThrowable(throwable: Throwable) {
                log.error("Background task failed", throwable)
            }
        }.queue()
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
