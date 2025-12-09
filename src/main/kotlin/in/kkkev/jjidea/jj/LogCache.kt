package `in`.kkkev.jjidea.jj

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache for log entries to avoid repeated jj log calls
 * Automatically invalidates when VCS changes occur
 */
@Service(Service.Level.PROJECT)
class LogCache(project: Project) : Disposable {

    private val log = Logger.getInstance(javaClass)
    private val cache = ConcurrentHashMap<String, CachedLogResult>()

    data class CachedLogResult(
        val entries: List<LogEntry>,
        val timestamp: Long = System.currentTimeMillis()
    )

    init {
        // Listen for VCS changes to invalidate cache
        project.messageBus.connect(this).subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, VcsListener {
            log.debug("VCS configuration changed, clearing log cache")
            clear()
        })
    }

    /**
     * Get cached log entries or null if not cached
     */
    fun get(revisions: Revset, filePaths: List<String> = emptyList()): List<LogEntry>? {
        val key = cacheKey(revisions, filePaths)
        val cached = cache[key]

        // Cache is valid for 30 seconds
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < 30_000) {
            log.debug("Cache hit for $key (${cached.entries.size} entries)")
            return cached.entries
        }

        // Cache miss or expired
        if (cached != null) {
            log.debug("Cache expired for $key")
            cache.remove(key)
        }

        return null
    }

    /**
     * Store log entries in cache
     */
    fun put(revisions: Revset, filePaths: List<String> = emptyList(), entries: List<LogEntry>) {
        val key = cacheKey(revisions, filePaths)
        cache[key] = CachedLogResult(entries)
        log.debug("Cached ${entries.size} entries for $key")
    }

    /**
     * Clear all cached entries
     */
    fun clear() {
        cache.clear()
        log.debug("Cleared log cache")
    }

    private fun cacheKey(revisions: Revset, filePaths: List<String>): String =
        "$revisions|${filePaths.sorted().joinToString(",")}"

    override fun dispose() {
        clear()
    }

    companion object {
        fun getInstance(project: Project): LogCache =
            project.getService(LogCache::class.java)
    }
}
