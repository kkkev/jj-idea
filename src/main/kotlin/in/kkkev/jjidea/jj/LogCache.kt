package `in`.kkkev.jjidea.jj

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsListener
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import `in`.kkkev.jjidea.settings.JujutsuSettings
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
     * Point lookup — falls back to a targeted `jj log -r <id>` if not cached.
     * Returns null only if jj has no such revision (not merely a cold cache).
     * Always call from a background thread.
     */
    @RequiresBackgroundThread
    operator fun get(id: ChangeId): LogEntry?

    @RequiresBackgroundThread
    operator fun get(id: CommitId): LogEntry?

    @RequiresBackgroundThread
    operator fun get(name: BookmarkName): LogEntry?

    /**
     * Fetch a context window around [id]: `ancestors(id, window) | id | descendants(id, window)`.
     * Stores all returned entries in the cache as a side effect (same fetch-and-store pattern as [all]).
     * Returns an empty list if [id] does not exist in the repository.
     * Always call from a background thread.
     */
    @RequiresBackgroundThread
    fun loadContext(id: ChangeId, window: Int = 10): List<LogEntry>

    /**
     * All bookmarks for this repo (`jj bookmark list`), including out-of-limit and deleted,
     * in jj's listing order. Loads on miss (synchronous I/O).
     * Always call from a background thread.
     */
    @get:RequiresBackgroundThread
    val bookmarks: List<BookmarkItem>

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
    // (topological order from the data loader). Updated under orderLock to prevent lost-write races
    // between concurrent loadContext and loadCommits calls.
    @Volatile private var orderedIds: List<ChangeId> = emptyList()
    private val orderLock = Any()

    @Volatile private var bookmarkCache: List<BookmarkItem>? = null

    init {
        repo.project.messageBus.connect(repo.project as Disposable).subscribe(
            ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
            VcsListener { clear() }
        )
    }

    override val bookmarks: List<BookmarkItem>
        get() = bookmarkCache
            ?: repo.logService.getBookmarks().getOrNull().orEmpty().also { bookmarkCache = it }

    override val all: List<LogEntry>
        get() {
            snapshot()?.let { return it }
            val settings = JujutsuSettings.getInstance(repo.project)
            val revset = settings.logRevset(repo).let { if (it.isBlank()) Revset.Default else Expression(it) }
            val limit = settings.logChangeLimit(repo)
            return repo.logService.getLogBasic(revset, limit = limit).getOrNull()
                ?.also { store(it) } ?: emptyList()
        }

    override operator fun get(id: ChangeId): LogEntry? =
        store[id]
            ?: repo.logService.getLogBasic(revset = id).getOrNull()?.firstOrNull()
                ?.also { store(listOf(it)) }

    override operator fun get(id: CommitId): LogEntry? =
        byCommitId[id]?.let { get(it) }
            ?: repo.logService.getLogBasic(revset = id).getOrNull()?.firstOrNull()
                ?.also { store(listOf(it)) }

    override operator fun get(name: BookmarkName): LogEntry? =
        byBookmark[name]?.let { store[it] }
            ?: repo.logService.getLogBasic(revset = name).getOrNull()?.firstOrNull()
                ?.also { store(listOf(it)) }

    override fun loadContext(id: ChangeId, window: Int): List<LogEntry> {
        // Fast path: if the target is already cached (e.g., from a previous loadContext call that
        // was then overwritten by loadCommits), return the full snapshot which includes both the
        // loadCommits entries and the previously-fetched context window.
        if (store.containsKey(id)) {
            return snapshot() ?: emptyList()
        }
        val revset = Expression("ancestors(${id.short}, $window) | ${id.short} | descendants(${id.short}, $window)")
        return repo.logService.getLogBasic(revset).getOrNull()
            ?.also { store(it) } ?: emptyList()
    }

    override fun store(entries: List<LogEntry>) {
        for (entry in entries) {
            store[entry.id]?.bookmarks?.forEach { byBookmark.remove(it.name) }
            store[entry.id] = entry
            byCommitId[entry.commitId] = entry.id
            entry.bookmarks.forEach { byBookmark[it.name] = entry.id }
        }
        val newIds = entries.map { it.id }.toHashSet()
        synchronized(orderLock) {
            orderedIds = orderedIds.filterNot { it in newIds } + entries.map { it.id }
        }
        log.debug("Stored ${entries.size} entries (repo=${repo.displayName})")
    }

    override fun clear() {
        val total = store.size
        store.clear()
        byCommitId.clear()
        byBookmark.clear()
        bookmarkCache = null
        synchronized(orderLock) {
            orderedIds = emptyList()
        }
        log.debug("Cleared $total entries (repo=${repo.displayName})")
    }

    private fun snapshot(): List<LogEntry>? =
        orderedIds.mapNotNull { store[it] }.ifEmpty { null }
}
