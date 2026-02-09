package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.*
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads commit log data from multiple repositories in parallel and updates the table model on EDT.
 *
 * Unified loader that:
 * - Loads commits from all provided repositories concurrently
 * - Merges results using topological sort (children before parents) with timestamp as tiebreaker
 * - Updates the table model and graph on EDT
 * - Subscribes to changeSelection for handling selection requests
 */
class UnifiedJujutsuLogDataLoader(
    private val project: Project,
    private val repositories: () -> List<JujutsuRepository>,
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
    private var pendingSelection: ChangeKey? = null

    @Volatile
    private var hasPendingSelection = false

    init {
        // Listen for change selection requests
        project.stateModel.changeSelection.connect(parentDisposable) { key -> requestSelection(key) }
    }

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

        if (!loading.compareAndSet(false, true)) {
            pendingRefresh.set(true)
            return
        }

        currentIndicator.get()?.cancel()

        object : Task.Backgroundable(project, "Loading Jujutsu Commits", true) {
            private val entriesByRepo = ConcurrentHashMap<JujutsuRepository, List<LogEntry>>()
            private val errors = ConcurrentHashMap<JujutsuRepository, Throwable>()
            private var allEntries: List<LogEntry> = emptyList()
            private var graphNodes: Map<ChangeId, GraphNode> = emptyMap()

            override fun run(indicator: ProgressIndicator) {
                currentIndicator.set(indicator)
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
                                    "Loaded ${loadedEntries.size} commits from ${
                                        repo.relativePath.ifEmpty { "root" }
                                    }"
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

                latch.await(5, TimeUnit.MINUTES)

                if (entriesByRepo.isEmpty() && errors.isNotEmpty()) {
                    log.error("All repositories failed to load")
                    return
                }

                allEntries = topologicalSort(entriesByRepo.values.flatten())

                log.info("Merged ${allEntries.size} commits from ${entriesByRepo.size} repositories")
                graphNodes = graphBuilder.buildGraph(allEntries)
            }

            override fun onSuccess() {
                loading.set(false)
                currentIndicator.set(null)

                if (entriesByRepo.isEmpty() && errors.isNotEmpty()) {
                    if (pendingRefresh.compareAndSet(true, false)) loadCommits(revset)
                    return
                }

                ApplicationManager.getApplication().invokeLater {
                    tableModel.setEntries(allEntries)
                    table.updateGraph(graphNodes)
                    log.info("Table updated with ${allEntries.size} commits and graph layout")

                    onDataLoaded?.invoke()

                    if (hasPendingSelection) {
                        hasPendingSelection = false
                        pendingSelection?.let { selectEntry(it.repo, it.revision) }
                        pendingSelection = null
                    }
                }

                if (pendingRefresh.compareAndSet(true, false)) loadCommits(revset)
            }

            override fun onThrowable(throwable: Throwable) {
                loading.set(false)
                currentIndicator.set(null)
                log.error("Background task failed", throwable)

                if (pendingRefresh.compareAndSet(true, false)) loadCommits(revset)
            }
        }.queue()
    }

    /**
     * Select an entry in the table by repo and revision, scrolling it into view.
     * Matches by repo to ensure correct selection in multi-root.
     *
     * @param repo The repository containing the entry
     * @param revision The revision to select ([ChangeId] or [WorkingCopy])
     */
    private fun selectEntry(repo: JujutsuRepository, revision: Revision) {
        val rowIndex = when (revision) {
            is ChangeId -> (0 until tableModel.rowCount).firstOrNull { row ->
                val entry = tableModel.getEntry(row)
                entry?.repo == repo && entry.id == revision
            }

            WorkingCopy -> (0 until tableModel.rowCount).firstOrNull { row ->
                val entry = tableModel.getEntry(row)
                entry?.repo == repo && entry.isWorkingCopy
            }

            else -> {
                log.warn("Unsupported revision type for selection: $revision")
                null
            }
        }

        if (rowIndex != null) {
            table.setRowSelectionInterval(rowIndex, rowIndex)
            table.scrollRectToVisible(table.getCellRect(rowIndex, 0, true))
            log.info("Selected entry at row $rowIndex (${repo.relativePath}:$revision)")
        } else {
            log.warn("Entry not found: ${repo.relativePath}:$revision")
        }
    }

    /**
     * Request selection of an entry. If data is loading, defers until load completes.
     */
    private fun requestSelection(key: ChangeKey) {
        pendingSelection = key
        hasPendingSelection = true
    }

    /**
     * Refresh the log - reload all commits from all repositories, preserving current selection.
     * Does not override explicit selection requests from changeSelection.
     */
    fun refresh() {
        log.info("Refreshing unified log")
        // Save current selection to restore after refresh (unless an explicit selection is pending)
        val savedSelection = table.takeIf { !hasPendingSelection }
            ?.selectedEntry
            ?.let { ChangeKey(it.repo, it.id) }

        loadCommits()

        // Restore selection only if no explicit selection is pending
        if (savedSelection != null && !hasPendingSelection) {
            requestSelection(savedSelection)
        }
    }
}

/**
 * Topologically sort log entries so children appear before parents.
 *
 * Uses Kahn's algorithm with timestamp as tiebreaker for unrelated entries.
 * This ensures the graph layout algorithm receives entries in the expected order
 * (children before parents) while maintaining a sensible visual ordering.
 *
 * @param entries List of log entries from one or more repositories
 * @return Entries sorted topologically (children before parents), with newer entries first among siblings
 */
internal fun topologicalSort(entries: List<LogEntry>): List<LogEntry> {
    if (entries.isEmpty()) return emptyList()

    // Build lookup maps
    val entryById = entries.associateBy { it.id }
    val entryIds = entryById.keys

    // Count children for each entry (only counting children that are in our set)
    val childCount = mutableMapOf<ChangeId, Int>()
    for (entry in entries) {
        childCount[entry.id] = 0
    }
    for (entry in entries) {
        for (parentId in entry.parentIds) {
            if (parentId in entryIds) {
                childCount[parentId] = childCount.getValue(parentId) + 1
            }
        }
    }

    // Priority queue ordered by timestamp (newest first) for tiebreaking
    val ready = PriorityQueue<LogEntry>(compareByDescending { it.authorTimestamp ?: it.committerTimestamp })

    // Start with entries that have no children in the set
    for (entry in entries) {
        if (childCount[entry.id] == 0) {
            ready.add(entry)
        }
    }

    // Process entries in topological order
    val result = mutableListOf<LogEntry>()
    while (ready.isNotEmpty()) {
        val entry = ready.poll()
        result.add(entry)

        // Decrement child count for each parent
        for (parentId in entry.parentIds) {
            if (parentId in entryIds) {
                val newCount = childCount.getValue(parentId) - 1
                childCount[parentId] = newCount
                if (newCount == 0) {
                    ready.add(entryById.getValue(parentId))
                }
            }
        }
    }

    return result
}
