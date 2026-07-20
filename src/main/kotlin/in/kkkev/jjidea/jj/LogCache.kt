package `in`.kkkev.jjidea.jj

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsListener
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.log.topologicalSort
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-repository log cache providing indexed access to [LogEntry] objects.
 *
 * Entries are cached between VCS operations and fully evicted via [clear] after any operation
 * (bookmark, fetch, describe, new, edit, …). Obtain via [JujutsuRepository.logCache].
 *
 * Note: [LogEntry.immutable] reflects jj's immutability of commit *content* (description, parents,
 * tree) but does not cover ref-derived data (bookmarks, ahead/behind counts, working-copy flag).
 * Those fields can change on any VCS operation, so we do not bucket by immutability.
 */
interface LogCache {
    /**
     * All entries for this repo — loads from jj on miss (synchronous I/O).
     * Always call from a background thread.
     */
    @get:RequiresBackgroundThread
    val all: List<LogEntry>

    /**
     * Lookup for any [Revision]. If a [ChangeId], then this is a point lookup falling back to a targeted
     * `jj log -r <id>` if not cached. If it is a commit id or bookmark name, then looks up by name, with fallback to
     * the log if not yet cached.
     * @throws IllegalArgumentException if [revision] does not point at a single change.
     */
    @RequiresBackgroundThread
    operator fun get(revision: Revision): LogEntry

    /**
     * Fetch a context window around [id]: `ancestors(id, window) | id | descendants(id, window)`.
     * Stores all returned entries in the cache as a side effect (same fetch-and-store pattern as [all]).
     * Returns an empty list if [id] does not exist in the repository.
     * Always call from a background thread.
     */
    @RequiresBackgroundThread
    fun loadContext(id: ChangeId, window: Int = 10): List<LogEntry>

    /**
     * Force a fresh reload: evict the cache and re-fetch all entries from jj.
     * Used by the log data loader on refresh so abandoned/rewritten commits are dropped.
     * @throws if the underlying [LogService.getLog] call fails.
     * Always call from a background thread.
     */
    @RequiresBackgroundThread
    fun reload(): List<LogEntry>

    /**
     * Populate the cache with freshly fetched entries.
     * Called by data loaders after a `jj log` fetch; general consumers should use [all] or [get].
     * Always call from a background thread.
     */
    @RequiresBackgroundThread
    fun store(entries: List<LogEntry>)

    /**
     * Evict all cached entries. Call after any VCS operation.
     * Safe from any thread — pure map operations, no I/O.
     */
    fun clear()
}

internal class RepoLogCache(private val repo: JujutsuRepository) : LogCache {
    private val log = Logger.getInstance(javaClass)

    private val store = ConcurrentHashMap<ChangeId, LogEntry>()
    private val byCommitId = ConcurrentHashMap<CommitId, ChangeId>()
    private val byBookmark = ConcurrentHashMap<BookmarkName, ChangeId>()

    // Tracks insertion order so snapshot() returns entries in the same order they were stored
    // (topological order from the data loader). LinkedHashSet gives O(1) remove+reinsert vs the
    // O(n) filterNot+concat that a List requires. All reads and writes synchronize on orderedIds.
    private val orderedIds = LinkedHashSet<ChangeId>()

    init {
        repo.project.messageBus.connect(repo.project as Disposable).subscribe(
            ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
            VcsListener { clear() }
        )
    }

    override val all: List<LogEntry>
        get() {
            snapshot()?.let { return it }
            val settings = JujutsuSettings.getInstance(repo.project)
            val revset = settings.logRevset(repo).let { if (it.isBlank()) Revset.Default else Expression(it) }
            val limit = settings.logChangeLimit(repo)
            return fetch(revset, limit = limit)
        }

    override fun get(revision: Revision): LogEntry = requireNotNull(
        when (revision) {
            is ChangeId -> store[revision]
            is CommitId -> byCommitId[revision]?.let(this::get)
            is BookmarkName -> byBookmark[revision]?.let(this::get)
            else -> null
        } ?: fetchOne(revision)
    ) { "Expression $revision does not point to a single LogEntry" }

    // quiet=true: resolving a single revision commonly fails on purpose (e.g. a free-form revision
    // typed by the user that may not exist), so that case is logged at INFO rather than WARN.
    private fun fetchOne(revision: Revision) = fetch(revision, quiet = true).firstOrNull()

    // Sorted so children always precede parents (newest first), matching what
    // UnifiedJujutsuLogDataLoader.loadCommits() stores after a main-log-window load - callers
    // of logCache.all/get/loadContext must see the same order regardless of whether the cache
    // was already warmed by the log window or is being cold-fetched here. CommitGraphBuilder
    // also requires this order to lay out connector lines correctly.
    private fun fetch(revset: Revset, limit: Int? = null, quiet: Boolean = false) =
        topologicalSort(repo.logService.getLog(revset = revset, limit = limit, quiet = quiet).getOrThrow())
            .also(this::store)

    // Fast path: if the target is already cached (e.g., from a previous loadContext call that
    // was then overwritten by loadCommits), return the full snapshot which includes both the
    // loadCommits entries and the previously-fetched context window.
    override fun loadContext(id: ChangeId, window: Int) = if (store.containsKey(id)) {
        snapshot() ?: emptyList()
    } else {
        val revset = Expression("ancestors(${id.short}, $window) | ${id.short} | descendants(${id.short}, $window)")
        fetch(revset)
    }

    override fun reload(): List<LogEntry> {
        clear()
        return all
    }

    override fun store(entries: List<LogEntry>) {
        for (entry in entries) {
            store[entry.id]?.bookmarks?.forEach { byBookmark.remove(it.name) }
            store[entry.id] = entry
            byCommitId[entry.commitId] = entry.id
            entry.bookmarks.forEach { byBookmark[it.name] = entry.id }
        }
        synchronized(orderedIds) {
            for (entry in entries) {
                orderedIds.remove(entry.id)
                orderedIds.add(entry.id)
            }
        }
        log.debug("Stored ${entries.size} entries (repo=${repo.displayName})")
    }

    override fun clear() {
        val total = store.size
        store.clear()
        byCommitId.clear()
        byBookmark.clear()
        synchronized(orderedIds) {
            orderedIds.clear()
        }
        log.debug("Cleared $total entries (repo=${repo.displayName})")
    }

    private fun snapshot(): List<LogEntry>? {
        val ids = synchronized(orderedIds) { orderedIds.toList() }
        return ids.mapNotNull { store[it] }.ifEmpty { null }
    }
}
