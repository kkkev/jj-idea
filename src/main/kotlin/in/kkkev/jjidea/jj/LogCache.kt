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
 * Immutable entries survive [invalidateMutable]; mutable entries are evicted after VCS operations.
 * Obtain via [JujutsuRepository.logCache].
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
     * Populate the cache with freshly fetched entries.
     * Called by data loaders after a `jj log` fetch; general consumers should use [all] or [get].
     * Always call from a background thread.
     */
    @RequiresBackgroundThread
    fun store(entries: List<LogEntry>)

    /**
     * Evict all mutable entries. Call after any VCS operation.
     * Safe from any thread — pure map operations, no I/O.
     */
    fun invalidateMutable()
}

internal class RepoLogCache(private val repo: JujutsuRepository) : LogCache {
    private val log = Logger.getInstance(javaClass)

    private val immutableStore = ConcurrentHashMap<ChangeId, LogEntry>()
    private val mutableStore = ConcurrentHashMap<ChangeId, LogEntry>()
    private val byCommitId = ConcurrentHashMap<CommitId, ChangeId>()
    private val byBookmark = ConcurrentHashMap<BookmarkName, ChangeId>()

    // Tracks insertion order so snapshot() returns entries in the same order they were stored
    // (topological order from the data loader). Rebuilt atomically on each store() call.
    @Volatile private var orderedIds: List<ChangeId> = emptyList()

    init {
        repo.project.messageBus.connect(repo.project as Disposable).subscribe(
            ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
            VcsListener { invalidateAll() }
        )
    }

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
        (immutableStore[id] ?: mutableStore[id])
            ?: repo.logService.getLogBasic(revset = id).getOrNull()?.firstOrNull()
                ?.also { store(listOf(it)) }

    override operator fun get(id: CommitId): LogEntry? =
        byCommitId[id]?.let { get(it) }
            ?: repo.logService.getLogBasic(revset = id).getOrNull()?.firstOrNull()
                ?.also { store(listOf(it)) }

    override operator fun get(name: BookmarkName): LogEntry? =
        byBookmark[name]?.let { immutableStore[it] ?: mutableStore[it] }
            ?: repo.logService.getLogBasic(revset = name).getOrNull()?.firstOrNull()
                ?.also { store(listOf(it)) }

    override fun store(entries: List<LogEntry>) {
        for (entry in entries) {
            val store = if (entry.immutable) immutableStore else mutableStore
            store[entry.id]?.bookmarks?.forEach { byBookmark.remove(it.name) }
            store[entry.id] = entry
            byCommitId[entry.commitId] = entry.id
            entry.bookmarks.forEach { byBookmark[it.name] = entry.id }
        }
        val newIds = entries.map { it.id }.toHashSet()
        orderedIds = orderedIds.filterNot { it in newIds } + entries.map { it.id }
        log.debug(
            "Stored ${entries.size} entries (${entries.count { it.immutable }} immutable, repo=${repo.displayName})"
        )
    }

    override fun invalidateMutable() {
        var evicted = 0
        mutableStore.keys.toSet().forEach { id ->
            mutableStore.remove(id)?.also { e ->
                byCommitId.remove(e.commitId)
                e.bookmarks.forEach { byBookmark.remove(it.name) }
                evicted++
            }
        }
        orderedIds = orderedIds.filter { immutableStore.containsKey(it) }
        log.debug(
            "Evicted $evicted mutable entries, ${immutableStore.size} immutable retained (repo=${repo.displayName})"
        )
    }

    private fun invalidateAll() {
        val total = immutableStore.size + mutableStore.size
        immutableStore.clear()
        mutableStore.clear()
        byCommitId.clear()
        byBookmark.clear()
        orderedIds = emptyList()
        log.debug("Evicted all $total entries (repo=${repo.displayName})")
    }

    private fun snapshot(): List<LogEntry>? =
        orderedIds.mapNotNull { immutableStore[it] ?: mutableStore[it] }.ifEmpty { null }
}
