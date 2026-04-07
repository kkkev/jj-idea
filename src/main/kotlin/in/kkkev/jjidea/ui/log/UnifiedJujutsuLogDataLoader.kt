package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.common.BackgroundDataLoader
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.util.runInBackground
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private val repositories: () -> Collection<JujutsuRepository>,
    private val panel: CommitTablePanel<Data>
) : BackgroundDataLoader(project, "Loading Jujutsu Commits") {
    private val graphBuilder = CommitGraphBuilder()

    data class Data(val entries: List<LogEntry>, val graphNodes: Map<ChangeId, GraphNode>, val limit: Int)

    override fun load() = loadCommits()

    private fun notify(data: Data) {
        panel.onDataLoaded(data)
        log.info("Table updated with ${data.entries.size} commits and graph layout")
    }

    /**
     * Load commits from all repositories in the background.
     *
     * @param revset Revision expression to load (default: all commits)
     */
    fun loadCommits() {
        val repos = repositories()
        val settings = JujutsuSettings.getInstance(project)
        val defaultLimit = settings.state.logChangeLimit

        if (repos.isEmpty()) {
            log.info("No repositories to load commits from")
            notify(Data(emptyList(), emptyMap(), defaultLimit))
            return
        }

        val entriesByRepo = ConcurrentHashMap<JujutsuRepository, List<LogEntry>>()
        val errors = ConcurrentHashMap<JujutsuRepository, Throwable>()
        var allEntries: List<LogEntry> = emptyList()
        var graphNodes: Map<ChangeId, GraphNode> = emptyMap()

        executeInBackground(
            run = { indicator ->
                indicator.text = "Loading commits from ${repos.size} repositories..."
                indicator.isIndeterminate = false

                val latch = CountDownLatch(repos.size)

                repos.forEachIndexed { index, repo ->
                    runInBackground {
                        try {
                            indicator.text2 = "Loading from ${repo.displayName}..."
                            indicator.fraction = index.toDouble() / repos.size

                            val repoLimit = settings.logChangeLimit(repo)
                            val revsetSetting = settings.logRevset(repo)
                            val revset: Revset =
                                if (revsetSetting.isBlank()) Revset.Default else Expression(revsetSetting)
                            val result = repo.logService.getLog(revset, limit = repoLimit)

                            result.onSuccess { loadedEntries ->
                                entriesByRepo[repo] = loadedEntries
                                log.info("Loaded ${loadedEntries.size} commits from ${repo.displayName}")
                            }.onFailure { e ->
                                errors[repo] = e
                                log.warn("Failed to load commits from $repo: ${e.message}")
                            }
                        } catch (e: Exception) {
                            errors[repo] = e
                            log.warn("Exception loading commits from $repo: ${e.message}")
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                latch.await(5, TimeUnit.MINUTES)

                if (entriesByRepo.isEmpty() && errors.isNotEmpty()) {
                    log.warn("All repositories failed to load - jj may not be installed")
                    return@executeInBackground
                }

                allEntries = topologicalSort(entriesByRepo.values.flatten())
                log.info("Merged ${allEntries.size} commits from ${entriesByRepo.size} repositories")
                graphNodes = graphBuilder.buildGraph(allEntries)
            },
            onSuccess = {
                if (entriesByRepo.isEmpty() && errors.isNotEmpty()) return@executeInBackground
                notify(Data(allEntries, graphNodes, defaultLimit))
            }
        )
    }

    override fun refresh() {
        log.info("Refreshing unified log")
        loadCommits()
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
