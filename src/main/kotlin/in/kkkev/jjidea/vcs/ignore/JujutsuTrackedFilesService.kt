package `in`.kkkev.jjidea.vcs.ignore

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.getChildPath
import `in`.kkkev.jjidea.vcs.relativeTo
import java.util.concurrent.ConcurrentHashMap

/**
 * On-demand, path-keyed cache for `jj file list` (tracked/untracked) answers, backing
 * [in.kkkev.jjidea.actions.file.TrackedToggleAction] and intended to also serve jj-idea-ih95's
 * "flag tracked-but-ignored files" need later.
 *
 * Unlike [JujutsuIgnoredFilesService] (a proactive whole-repo-tree scanner), this never walks
 * anything - it only ever holds entries for paths some caller actually asked about via
 * [requestRefresh], so it stays small regardless of repo size.
 *
 * ## Why this exists
 * `jj file list` is a subprocess call, and IntelliJ's `ActionUpdateThread.BGT` still runs
 * `update()`/`isSelected()` under a read action - `OSProcessHandler.checkEdtAndReadAction`
 * explicitly forbids waiting on a process synchronously there (and on the EDT). This service lets
 * those methods do an instant, synchronous, in-memory cache read instead, kicking off a
 * non-blocking background refresh on a cache miss (see jj-idea-i9ol round 4 design notes).
 *
 * ## Threading
 * - [requestRefresh] / [invalidatePaths] / [invalidate]: safe from any thread, never block.
 * - The actual `jj file list` call runs on a pooled thread via [queue]
 *   (`executeInDispatchThread = false`, same as [JujutsuIgnoredFilesService]'s `scan`).
 * - [trackedStateOrNull]: lock-free read of a [ConcurrentHashMap].
 */
@Service(Service.Level.PROJECT)
class JujutsuTrackedFilesService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(javaClass)

    // internal (not private) so tests can drive `refresh` directly - see its doc comment.
    internal class RepoState {
        val cache = ConcurrentHashMap<String, Boolean>()
        val pending = ConcurrentHashMap.newKeySet<String>()
    }

    private val states = ConcurrentHashMap<String, RepoState>()

    // Batches run on a pooled thread; 300ms coalescing window. Each batch is queued with a unique
    // identity (see requestRefresh) rather than a shared per-repo one, since MergingUpdateQueue
    // coalesces same-identity updates and keeps only the latest - a shared identity would silently
    // drop earlier-requested paths, leaving them stuck in `pending` forever.
    private val queue = MergingUpdateQueue("jjTrackedFiles", 300, true, null, this, null, false)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Synchronous, non-blocking, instant in-memory read - safe to call from `update()`/
     * `isSelected()`. `null` means unknown (never queried, or invalidated); callers should show a
     * safe default and also call [requestRefresh].
     */
    fun trackedStateOrNull(repo: JujutsuRepository, path: FilePath): Boolean? =
        states[repo.directory.path]?.cache?.get(path.relativeTo(repo.directory))

    /**
     * Non-blocking: for any of [paths] not already cached or already in flight, enqueues a single
     * batched `jj file list` query on a pooled thread. Safe to call from `update()`/`isSelected()` -
     * this only ever schedules work, it never waits for it.
     */
    fun requestRefresh(repo: JujutsuRepository, paths: List<FilePath>) {
        if (paths.isEmpty()) return
        val state = states.getOrPut(repo.directory.path) { RepoState() }
        val relativePaths = paths.map { it.relativeTo(repo.directory) }
        val toQueue = relativePaths.filter { !state.cache.containsKey(it) && state.pending.add(it) }
        if (toQueue.isEmpty()) return

        queue.queue(Update.create(Any()) { refresh(repo, state, toQueue) })
    }

    /**
     * Directly sets cached entries without going through a jj query - for optimistic UI updates
     * (see [in.kkkev.jjidea.actions.file.TrackedToggleAction]: set the expected value the instant
     * the user clicks, then keep it on success or revert it on failure, rather than invalidating
     * and waiting on a fresh async round-trip either way).
     */
    fun setKnown(repo: JujutsuRepository, paths: List<FilePath>, tracked: Boolean) {
        val state = states.getOrPut(repo.directory.path) { RepoState() }
        for (path in paths) state.cache[path.relativeTo(repo.directory)] = tracked
    }

    /** Removes cached entries for specific paths (e.g. after an ordinary VFS edit to those files). */
    fun invalidatePaths(repo: JujutsuRepository, paths: List<FilePath>) {
        val state = states[repo.directory.path] ?: return
        for (path in paths) state.cache.remove(path.relativeTo(repo.directory))
    }

    /** Clears the whole repo's cache (e.g. after a track/untrack command, or a repo-set change). */
    fun invalidate(repo: JujutsuRepository) {
        states.remove(repo.directory.path)
    }

    /** Test-only accessor for the per-repo state, to pair with [refresh] in tests. */
    internal fun stateFor(repo: JujutsuRepository): RepoState = states.getOrPut(repo.directory.path) { RepoState() }

    companion object {
        fun getInstance(project: Project): JujutsuTrackedFilesService = project.service()
    }

    // ── Internal refresh machinery ────────────────────────────────────────────

    // Queue disposes itself via Disposer because we passed `this` as its parent.
    override fun dispose() {}

    /**
     * Runs the actual `jj file list` batch and populates the cache. `internal` (rather than
     * `private`) so tests can call it directly and assert on the resulting cache state without
     * waiting on the real queue's 300ms debounce - [JujutsuIgnoredFilesService]'s own async
     * timing is likewise treated as a manual-test surface, not a unit-test target.
     */
    internal fun refresh(repo: JujutsuRepository, state: RepoState, relativePaths: List<String>) {
        try {
            val result = repo.commandExecutor.fileList(relativePaths.map { repo.directory.getChildPath(it) })
            val trackedSet = result.stdout.lineSequence()
                .map { it.trim() }
                .filterTo(mutableSetOf()) { it.isNotEmpty() }
            for (rel in relativePaths) {
                state.cache[rel] = rel in trackedSet
            }
        } catch (e: Exception) {
            log.warn("tracked-file refresh failed for ${repo.directory.name}: ${e.message}", e)
        } finally {
            state.pending.removeAll(relativePaths.toSet())
        }
    }
}
