package `in`.kkkev.jjidea.vcs.ignore

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.changes.VcsManagedFilesHolder
import com.intellij.openapi.vcs.util.paths.RecursiveFilePathSet
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.measurePerf
import `in`.kkkev.jjidea.vcs.changes.collectTrackedAbsolutePaths
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** How long before the slow-scan watchdog fires (same threshold as the former inline scan in JujutsuChangeProvider). */
internal const val IGNORE_SCAN_WATCHDOG_MS = 5_000L

/**
 * Asynchronous engine for ignored-file scanning, backing [JujutsuVcsIgnoredFilesHolder].
 *
 * Decouples ignored-file computation from [JujutsuChangeProvider.getChanges] so that
 * modified-file refresh latency is independent of ignored-tree size.  Design mirrors
 * git4idea's `GitUntrackedFilesHolder`, adapted to this plugin's project-level-service
 * architecture (all IDE event subscriptions are centralised in [JujutsuStateModel]).
 *
 * ## Threading
 * - [markDirty] / [invalidate]: safe from any thread
 * - [scan]: runs on a pooled thread via [queue]; updates coalesce by repo-path identity so
 *   no two scans for the same repo overlap — no version-counter or cancellation needed
 * - [containsFile] / [values] / [isInUpdatingMode]: lock-free reads of @Volatile fields
 */
@Service(Service.Level.PROJECT)
class JujutsuIgnoredFilesService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(javaClass)

    // `RepoState` is its own monitor: `synchronized(state)` guards the dirty scope
    // (`dirtyPaths` + `fullRescan`). It is a private, fully-controlled class so no
    // external code can contend on its monitor — no dedicated lock object needed.
    private class RepoState(val repo: JujutsuRepository) {
        // guarded by synchronized(this)
        val dirtyPaths = HashSet<FilePath>()
        var fullRescan = true

        /** True from [enqueue] until the scan finishes; drives the platform's updating indicator. */
        @Volatile
        var updating = true

        /** Replaced atomically on each completed scan; readers are lock-free. */
        @Volatile
        var ignored = RecursiveFilePathSet(repo.directory.isCaseSensitive)
    }

    private val states = ConcurrentHashMap<String, RepoState>()

    // Scans run on a pooled thread (executeInDispatchThread = false); 500ms coalescing window.
    // Parented to `this` so the queue disposes via Disposer.
    private val queue = MergingUpdateQueue("jjIgnoredFiles", 500, true, null, this, null, false)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Marks specific files dirty for an incremental re-check.
     * Call this when working-copy files change (from [JujutsuStateModel]'s VFS listener).
     */
    fun markDirty(repo: JujutsuRepository, files: Collection<FilePath>) {
        if (files.isEmpty()) return
        val state = states.getOrPut(repo.directory.path) { RepoState(repo) }
        synchronized(state) {
            if (!state.fullRescan) state.dirtyPaths.addAll(files)
        }
        enqueue(state)
    }

    /**
     * Triggers a full ignored-file rescan for [repo].
     * Call on repo discovery, .gitignore change, or settings change.
     */
    fun invalidate(repo: JujutsuRepository) {
        val state = states.getOrPut(repo.directory.path) { RepoState(repo) }
        synchronized(state) {
            state.fullRescan = true
            state.dirtyPaths.clear()
        }
        enqueue(state)
    }

    /** Drops the cached state for [repo] (call when a repo is removed). */
    fun remove(repo: JujutsuRepository) {
        states.remove(repo.directory.path)
    }

    fun isInUpdatingMode() = states.values.any { it.updating }

    fun containsFile(file: FilePath, vcsRoot: VirtualFile) =
        states[vcsRoot.path]?.ignored?.hasAncestor(file) ?: false

    fun values(): Collection<FilePath> = states.values.flatMap { it.ignored.filePaths() }

    companion object {
        fun getInstance(project: Project): JujutsuIgnoredFilesService = project.service()
    }

    // ── Internal scan machinery ───────────────────────────────────────────────

    // Queue disposes itself via Disposer because we passed `this` as its parent.
    override fun dispose() {}

    private fun publishUpdatingMode() =
        project.messageBus.syncPublisher(VcsManagedFilesHolder.TOPIC).updatingModeChanged()

    private fun enqueue(state: RepoState) {
        state.updating = true
        publishUpdatingMode() // notify before queuing so the spinner appears immediately
        queue.queue(Update.create(state.repo.directory.path) { scan(state) })
    }

    private fun scan(state: RepoState) {
        val repo = state.repo
        try {
            if (JujutsuSettings.getInstance(project).disableIgnoredFileScanning(repo)) {
                log.info("ignore-scan: skipped for ${repo.directory.name} (disabled in settings)")
                synchronized(state) {
                    state.fullRescan = false
                    state.dirtyPaths.clear()
                }
                state.ignored = RecursiveFilePathSet(repo.directory.isCaseSensitive)
                return
            }

            // Snapshot and clear the dirty scope (git's acquireDirt pattern)
            val doFullRescan: Boolean
            val dirtySnapshot: List<FilePath>
            synchronized(state) {
                // Escalate to full scan if the repo root itself is in the dirty set
                val rootInDirty = state.dirtyPaths.any {
                    it.path == repo.directory.path || it.path + "/" == repo.directory.path
                }
                doFullRescan = state.fullRescan || rootInDirty
                dirtySnapshot = if (doFullRescan) emptyList() else state.dirtyPaths.toList()
                state.fullRescan = false
                state.dirtyPaths.clear()
            }

            // Files reported by `jj status` must not also appear as ignored
            val trackedPaths: Set<String> = run {
                val result = repo.commandExecutor.status()
                if (result.isSuccess) collectTrackedAbsolutePaths(result.stdout, repo) else emptySet()
            }

            val ignoreService = JujutsuIgnoreService.getInstance(project)
            val cache = ignoreService.getCache(repo.directory)
            val repoRoot = File(repo.directory.path)

            var newIgnored = RecursiveFilePathSet(repo.directory.isCaseSensitive)
            if (doFullRescan) {
                // Full scan — use the pruned walker from GitignoreCache
                val scanStart = System.currentTimeMillis()
                try {
                    log.measurePerf("ignore-scan", repo.directory.name) { report ->
                        val stats = cache.collectIgnored(
                            FileScanNode(repoRoot),
                            {
                                if (System.currentTimeMillis() - scanStart > IGNORE_SCAN_WATCHDOG_MS) {
                                    val elapsed = System.currentTimeMillis() - scanStart
                                    log.warn(
                                        "ignore-scan: watchdog triggered after ${elapsed}ms for " +
                                            "${repo.directory.name}; aborting scan"
                                    )
                                    JujutsuNotifications.notifyIgnoreScanSlow(project, repo, elapsed)
                                    throw ProcessCanceledException()
                                }
                            }
                        ) { relPath, isDir ->
                            if (repo.directory.path + "/" + relPath !in trackedPaths) {
                                newIgnored.add(VcsUtil.getFilePath(File(repoRoot, relPath), isDir))
                            }
                        }
                        report.count("visited", stats.visited)
                        report.count("ignored", stats.ignored)
                    }
                } catch (e: ProcessCanceledException) {
                    log.warn(
                        "ignore-scan: aborted for ${repo.directory.name} after watchdog; " +
                            "ignored-file set left empty for this scan"
                    )
                    // Abandon the partial walk's results rather than report an incomplete ignored-set.
                    newIgnored = RecursiveFilePathSet(repo.directory.isCaseSensitive)
                }
            } else {
                // Incremental — retain entries not covered by dirty paths, re-check dirty paths
                val dirtySet = RecursiveFilePathSet(repo.directory.isCaseSensitive)
                    .apply { dirtySnapshot.forEach { add(it) } }
                for (fp in state.ignored.filePaths()) {
                    if (!dirtySet.hasAncestor(fp)) newIgnored.add(fp)
                }
                for (fp in dirtySnapshot) {
                    if (fp.path !in trackedPaths && ignoreService.isIgnored(fp, repo.directory)) {
                        if (!newIgnored.hasAncestor(fp)) newIgnored.add(fp)
                    }
                }
            }

            state.ignored = newIgnored
        } catch (e: Exception) {
            log.warn("ignore-scan: unexpected error for ${repo.directory.name}: ${e.message}", e)
        } finally {
            synchronized(state) { state.updating = state.fullRescan || state.dirtyPaths.isNotEmpty() }
            publishUpdatingMode()
            ChangeListManagerImpl.getInstanceImpl(project).notifyUnchangedFileStatusChanged()
        }
    }
}
