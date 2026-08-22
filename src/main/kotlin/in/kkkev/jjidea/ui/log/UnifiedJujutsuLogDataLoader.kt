package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.common.BackgroundDataLoader
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
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

    data class Data(val entries: List<LogEntry>, val graphNodes: Map<ChangeKey, GraphNode>, val limit: Int)

    @Volatile private var lastLimit: Int = 0

    // Per-repo expansion entries accumulated by navigation; keyed by repo identity.
    // Never discarded by loadCommits — only cleared on explicit Refresh (clearExpansions).
    private val expansionEntriesByRepo = ConcurrentHashMap<JujutsuRepository, List<LogEntry>>()

    // Per-repo whole-repo search results (jj-idea-lpbv), accumulated the same way as
    // expansionEntriesByRepo above. Never discarded by loadCommits — only cleared on explicit
    // Refresh (clearExpansions).
    private val searchEntriesByRepo = ConcurrentHashMap<JujutsuRepository, List<LogEntry>>()

    override fun load() = loadCommits()

    private fun notify(data: Data) {
        lastLimit = data.limit
        panel.onDataLoaded(data)
        log.info("Table updated with ${data.entries.size} commits and graph layout")
    }

    /**
     * Load commits from all repositories in the background.
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
        var graphNodes: Map<ChangeKey, GraphNode> = emptyMap()

        executeInBackground(
            run = { indicator ->
                indicator.text = "Loading commits from ${repos.size} repositories..."
                indicator.isIndeterminate = false

                val latch = CountDownLatch(repos.size)
                val deletedNamesByRepo = ConcurrentHashMap<JujutsuRepository, Set<String>>()

                repos.forEachIndexed { index, repo ->
                    runInBackground {
                        try {
                            indicator.text2 = "Loading from ${repo.displayName}..."
                            indicator.fraction = index.toDouble() / repos.size

                            val loadedEntries = repo.logCache.reload()
                            entriesByRepo[repo] = loadedEntries
                            log.info("Loaded ${loadedEntries.size} commits from ${repo.displayName}")

                            repo.logService.getBookmarks().onSuccess { bookmarkItems ->
                                deletedNamesByRepo[repo] = bookmarkItems
                                    .filter { it.bookmark.deleted && !it.bookmark.isRemote }
                                    .map { it.bookmark.localName }
                                    .toSet()
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
                    .map { entry -> enrichWithDeletedBookmarks(entry, deletedNamesByRepo[entry.repo] ?: emptySet()) }
                log.info("Merged ${allEntries.size} commits from ${entriesByRepo.size} repositories")
                allEntries.groupBy { it.repo }.forEach { (repo, entries) -> repo.logCache.store(entries) }
                graphNodes = graphBuilder.buildGraph(allEntries)
            },
            onSuccess = {
                if (entriesByRepo.isEmpty() && errors.isNotEmpty()) return@executeInBackground
                notify(Data(allEntries, graphNodes, defaultLimit))
            }
        )
    }

    fun loadExpanding(repo: JujutsuRepository, changeId: ChangeId) {
        val window = JujutsuSettings.getInstance(project).logContextWindow(repo)
        runInBackground {
            repo.logCache.loadContext(changeId, window).takeUnless { it.isEmpty() }?.let { expansion ->
                expansionEntriesByRepo[repo] = expansion
                mergeAndNotify()
            }
        }
    }

    /**
     * Runs the whole-repo search revset (jj-idea-lpbv) against every repository and merges any
     * commits found into the loaded set, so results outside the log window (e.g. a pasted Git
     * hash or a match in an older commit's description) become visible. One `jj log -r` call per
     * repository, same cost envelope as [loadExpanding].
     *
     * [onComplete] is invoked on EDT after the table has been updated, with the number of
     * returned entries that were not already part of the currently loaded set — so the caller
     * can distinguish "found nothing" from "found only what was already showing".
     */
    fun searchWholeRepo(
        query: String,
        useRegex: Boolean,
        matchCase: Boolean,
        wholeWords: Boolean,
        onComplete: (Int) -> Unit
    ) {
        val revset = logSearchRevset(query, useRegex, matchCase, wholeWords)
        if (revset == null) {
            onComplete(0)
            return
        }
        val repos = repositories()
        val settings = JujutsuSettings.getInstance(project)
        runInBackground {
            val alreadyLoaded = repos.flatMap { it.logCache.all + (expansionEntriesByRepo[it] ?: emptyList()) }
                .mapTo(HashSet()) { it.key }
            val resultsByRepo = fetchSearchResults(repos, revset) { repo -> settings.logChangeLimit(repo) }
            var newCount = 0
            resultsByRepo.forEach { (repo, entries) ->
                repo.logCache.store(entries)
                searchEntriesByRepo[repo] = entries
                newCount += entries.count { it.key !in alreadyLoaded }
            }
            mergeAndNotify()
            runLater { onComplete(newCount) }
        }
    }

    /**
     * Rebuilds the merged (loaded + expansion + search) entry set for every repo, sorts it, and
     * notifies the panel on EDT. Shared by [loadExpanding] and [searchWholeRepo] — both accumulate
     * additive per-repo buckets on top of [JujutsuRepository.logCache], never discarded except by
     * [clearExpansions] on an explicit Refresh. Must be called from a background thread, since it
     * reads [JujutsuRepository.logCache].
     */
    private fun mergeAndNotify() {
        val allEntries = repositories().flatMap { r ->
            val regular = r.logCache.all
            val expanded = expansionEntriesByRepo[r] ?: emptyList()
            val searched = searchEntriesByRepo[r] ?: emptyList()
            regular + expanded + searched
        }
        val merged = topologicalSort(allEntries.distinctBy { it.key })
        val data = Data(merged, graphBuilder.buildGraph(merged), lastLimit)
        runLater { notify(data) }
    }

    override fun clearExpansions() {
        expansionEntriesByRepo.clear()
        searchEntriesByRepo.clear()
    }

    override fun refresh() {
        log.info("Refreshing unified log")
        loadCommits()
    }
}

/**
 * Runs [revset] against each of [repos] via `jj log -r`, capped per-repo at [limitFor]. One
 * process invocation per repo — O(#repos), independent of the number of commits loaded or the
 * repo's total history size.
 *
 * `quiet = true` because a whole-repo search revset failing to resolve (e.g. the query happens
 * to look like a `present(...)` revision that doesn't exist) is an expected, user-triggered
 * outcome, not a bug — logged at INFO rather than WARN (same convention as
 * [in.kkkev.jjidea.jj.RepoLogCache.fetchOne]). A repo whose fetch fails is simply omitted from
 * the result map rather than failing the whole search.
 */
internal fun fetchSearchResults(
    repos: Collection<JujutsuRepository>,
    revset: Expression,
    limitFor: (JujutsuRepository) -> Int
): Map<JujutsuRepository, List<LogEntry>> = repos.mapNotNull { repo ->
    repo.logService.getLog(revset = revset, limit = limitFor(repo), quiet = true)
        .getOrNull()
        ?.takeUnless { it.isEmpty() }
        ?.let { repo to it }
}.toMap()

/**
 * Injects pending-deletion local bookmarks into log entries and zeroes out garbage ahead/behind counts.
 *
 * When a local bookmark is deleted (`jj bookmark delete foo`) but the remote `foo@origin` still exists,
 * `jj log` omits the deleted local from the entry (it has no target) while `foo@origin` reports a huge
 * `tracking_ahead_count` (distance from absent local to root). This function corrects both problems:
 * - Injects `Bookmark("foo", tracked=true, deleted=true)` at the entry that carries `foo@origin`
 * - Replaces `foo@origin` with zeroed ahead/behind counts (the original values are meaningless)
 */
internal fun enrichWithDeletedBookmarks(entry: LogEntry, deletedNames: Set<String>): LogEntry {
    if (deletedNames.isEmpty()) return entry
    val remotes = entry.bookmarks.filter { it.isRemote && it.localName in deletedNames }
    if (remotes.isEmpty()) return entry
    val injectedLocals = remotes.map { Bookmark(it.localName, tracked = true, deleted = true) }
    val cleanedRemotes = remotes.map { it.copy(aheadCount = 0, behindCount = 0) }
    val remaining = entry.bookmarks.filter { !it.isRemote || it.localName !in deletedNames }
    return entry.copy(bookmarks = remaining + injectedLocals + cleanedRemotes)
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
    val entryByKey = entries.associateBy(LogEntry::key)
    val entryKeys = entryByKey.keys

    // Count children for each entry (only counting children that are in our set)
    val childCount = mutableMapOf<ChangeKey, Int>()
    for (entry in entries) {
        childCount[entry.key] = 0
    }
    for (entry in entries) {
        for (parentKey in entry.parentKeys) {
            if (parentKey in entryKeys) {
                childCount[parentKey] = childCount.getValue(parentKey) + 1
            }
        }
    }

    // Priority queue ordered by timestamp (newest first) for tiebreaking
    val ready = PriorityQueue<LogEntry>(compareByDescending { it.authorTimestamp ?: it.committerTimestamp })

    // Start with entries that have no children in the set
    for (entry in entries) {
        if (childCount[entry.key] == 0) {
            ready.add(entry)
        }
    }

    // Process entries in topological order
    val result = mutableListOf<LogEntry>()
    while (ready.isNotEmpty()) {
        val entry = ready.poll()
        result.add(entry)

        // Decrement child count for each parent
        for (parentKey in entry.parentKeys) {
            if (parentKey in entryKeys) {
                val newCount = childCount.getValue(parentKey) - 1
                childCount[parentKey] = newCount
                if (newCount == 0) {
                    ready.add(entryByKey.getValue(parentKey))
                }
            }
        }
    }

    return result
}
