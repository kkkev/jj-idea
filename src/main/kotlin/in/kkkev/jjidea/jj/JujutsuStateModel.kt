package `in`.kkkev.jjidea.jj

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsListener
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.ex.ProjectLevelVcsManagerEx
import com.intellij.openapi.vcs.ex.VcsActivationListener
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import `in`.kkkev.jjidea.util.notifiableState
import `in`.kkkev.jjidea.util.simpleNotifier
import `in`.kkkev.jjidea.vcs.JujutsuVcs.Companion.DOT_JJ
import `in`.kkkev.jjidea.vcs.jujutsuRepositories
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central state model for Jujutsu VCS data.
 *
 * ## Architecture
 *
 * ```
 * IDE Events (all subscribed here):
 *   VFS_CHANGES (.jj create/delete) ──┐
 *   VCS_CONFIGURATION_CHANGED ────────┼──→ initializedRoots.invalidate()
 *   VCS_ACTIVATED ────────────────────┘        │
 *                                              ├──→ repositoryStates.invalidate()
 *                                              ├──→ logRefresh.notify()
 *                                              └──→ ToolWindowEnabler
 *
 *   VFS_CHANGES (file content) ──→ 300ms debounce ──→ repositoryStates.invalidate()
 *                                                     + logRefresh.notify()
 *
 *   repo.invalidate(select) ──→ repositoryStates.invalidate()
 *     (VCS operations)          + logRefresh.notify()
 *                               + changeSelection.notify()
 * ```
 *
 * ## Threading
 *
 * - [NotifiableState.invalidate]: safe from any thread; loader runs on pooled thread
 * - [NotifiableState.Listener]: called on EDT via [com.intellij.openapi.application.ApplicationManager.invokeLater]
 * - [Notifier.notify]: posts to EDT
 * - BulkFileListener: called on VFS thread (background)
 *
 * ## Initialization Order
 *
 * 1. Constructor registers all subscriptions (VFS, VCS config, VCS activated)
 * 2. Explicit [initializedRoots.invalidate] fires initial root scan
 * 3. initializedRoots cascade → repositoryStates + logRefresh
 * 4. ToolWindowEnabler connects to initializedRoots separately
 *
 * Note: [SimpleNotifiableState] does NOT auto-invalidate on construction.
 * Callers must call [invalidate] explicitly to trigger the first load.
 */
@Service(Service.Level.PROJECT)
class JujutsuStateModel(private val project: Project) : Disposable {
    private val log = Logger.getInstance(javaClass)

    /** Counter for [suppressRefresh]/[resumeRefresh]. When > 0, file-change refreshes are suppressed. */
    private val refreshSuppression = AtomicInteger(0)

    /**
     * Set of VCS-configured JJ roots that are actually initialized (have .jj directory).
     * Cached to avoid EDT slow operations. Updated via file listener when .jj directories change.
     */
    val initializedRoots = notifiableState(project, "Jujutsu Initialized Roots", emptySet()) {
        // This runs on background thread - safe to call VCS manager
        project.jujutsuRepositories.filter { it.isInitialised }.toSet()
    }

    /**
     * Whether this project has any initialized JJ repositories.
     * Safe to call from EDT - uses cached value.
     */
    val isJujutsu: Boolean get() = initializedRoots.value.isNotEmpty()

    val repositoryStates = notifiableState(
        project,
        "Jujutsu Repository States",
        emptySet(),
        equalityCheck = { a, b ->
            a.map { it.stateKey }.toSet() == b.map { it.stateKey }.toSet()
        }
    ) {
        // Use initializedRoots.value to only load state for initialized repositories
        // This avoids errors from uninitialized JJ VCS mappings
        initializedRoots.value.mapNotNull {
            it.logService.getLog(WorkingCopy).getOrNull()?.firstOrNull()
        }.toSet()
    }

    /**
     * Log refresh notifier. Fires when the log should reload, either because:
     * - A VCS operation completed (via [JujutsuRepository.invalidate])
     * - Working copy state changed (cascaded from [repositoryStates])
     *
     * The log subscribes to this instead of [repositoryStates] directly, because
     * [repositoryStates] only tracks the working copy entry and won't fire for
     * operations on non-working-copy commits (abandon, describe, rebase, etc.).
     */
    val logRefresh = simpleNotifier<Unit>(project, "Jujutsu Log Refresh")

    /**
     * Change selection notifier. Actions use this to request a specific change be selected.
     * Log panels and data loaders listen and select the matching entry if it belongs to their repo.
     */
    val changeSelection = simpleNotifier<ChangeKey>(project, "Jujutsu Change Selection")

    private val repositoryStateAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /** Suppress file-change refreshes (e.g., during batch operations). Pairs with [resumeRefresh]. */
    fun suppressRefresh() {
        refreshSuppression.incrementAndGet()
    }

    /** Resume file-change refreshes after [suppressRefresh]. Triggers a refresh if the counter reaches zero. */
    fun resumeRefresh() {
        if (refreshSuppression.decrementAndGet() <= 0) {
            scheduleRepositoryRefresh()
        }
    }

    private fun scheduleRepositoryRefresh() {
        repositoryStateAlarm.cancelAllRequests()
        repositoryStateAlarm.addRequest({
            repositoryStates.invalidate()
            logRefresh.notify(Unit)
        }, 300)
    }

    init {
        // Fire off an initial invalidation of repository roots to transition from empty to the actual roots - so that
        // initial state is initialised in all listeners
        initializedRoots.invalidate()

        val connection = project.messageBus.connect(this)

        // Watch for file changes to mark files dirty and detect .jj directory changes
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    // Check for .jj directory changes to update initializedRoots
                    if (events.any { isJjDirectoryEvent(it) }) {
                        initializedRoots.invalidate()
                    }

                    // Mark changed files as dirty for VCS refresh
                    // Uses cached initializedRoots to avoid EDT slow operations
                    val repos = initializedRoots.value
                    if (repos.isEmpty()) return

                    val dirtyScopeManager = VcsDirtyScopeManager.getInstance(project)
                    var hasRepoChanges = false
                    events.forEach { event ->
                        when (event) {
                            is VFileContentChangeEvent, is VFileCreateEvent, is VFileDeleteEvent -> {
                                event.file?.let { file ->
                                    if (repos.any { VfsUtil.isAncestor(it.directory, file, false) } &&
                                        !isUnderJjDirectory(file, repos)
                                    ) {
                                        dirtyScopeManager.fileDirty(file)
                                        hasRepoChanges = true
                                    }
                                }
                            }
                        }
                    }

                    if (hasRepoChanges) {
                        if (refreshSuppression.get() > 0) {
                            log.info("File changes detected but refresh suppressed, skipping")
                            return
                        }
                        log.info(
                            "File changes detected (${events.size} events on " +
                                "${Thread.currentThread().name}), scheduling repositoryStates invalidation"
                        )
                        scheduleRepositoryRefresh()
                    }
                }

                private fun isUnderJjDirectory(file: VirtualFile, repos: Set<JujutsuRepository>) =
                    repos.any { repo ->
                        repo.directory.findChild(DOT_JJ)
                            ?.let { VfsUtil.isAncestor(it, file, false) } ?: false
                    }

                private fun isJjDirectoryEvent(event: VFileEvent): Boolean {
                    val name = event.file?.name ?: event.path.substringAfterLast('/')
                    return name == DOT_JJ && (event is VFileCreateEvent || event is VFileDeleteEvent)
                }
            }
        )

        // When jj becomes available (e.g., user upgrades from VersionTooOld, or changes path),
        // reload repository states and log. Without this, repositoryStates stays empty from the
        // failed load when jj was unavailable, and the working copy panel shows "No repositories".
        JjAvailabilityChecker.getInstance(project).status.connect(this) { status ->
            if (status is JjAvailabilityStatus.Available) {
                log.info("jj became available (${status.version}), refreshing state")
                repositoryStates.invalidate()
                logRefresh.notify(Unit)
            }
        }

        // Subscribe to VCS configuration changes
        connection.subscribe(
            ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
            VcsListener {
                log.debug("VCS configuration changed")
                initializedRoots.invalidate()
            }
        )
        connection.subscribe(
            ProjectLevelVcsManagerEx.VCS_ACTIVATED,
            VcsActivationListener {
                log.debug("VCS activated")
                initializedRoots.invalidate()
            }
        )

        // Invalidate repositoryStates since it depends on initializedRoots
        initializedRoots.connect(this) { new ->
            log.info("Initialized roots changed to ${new.size} roots")
            // Reload repository states now that we have the current roots
            repositoryStates.invalidate()
            // Also reload the log
            logRefresh.notify(Unit)
        }

        // When repository states change (VCS operations completed), mark directories dirty
        // to trigger ChangeProvider refresh for file changes.
        // Only dirty repos whose state actually changed to avoid flooding FileStatusManager.
        var previousStateKeys = emptyMap<JujutsuRepository, LogEntry.StateKey>()
        repositoryStates.connect(this) { new ->
            val newKeys = new.associate { it.repo to it.stateKey }
            val changedEntries = new.filter { entry -> previousStateKeys[entry.repo] != entry.stateKey }
            previousStateKeys = newKeys
            if (changedEntries.isNotEmpty()) {
                log.info("Repository states changed for ${changedEntries.size}/${new.size} repos, marking dirty")
                val dirtyScopeManager = VcsDirtyScopeManager.getInstance(project)
                changedEntries.forEach { entry ->
                    dirtyScopeManager.dirDirtyRecursively(entry.repo.directory)
                }
            }
        }
    }

    override fun dispose() {}
}

val Project.stateModel: JujutsuStateModel get() = service()

/**
 * Invalidate cached repository state and optionally request a change selection.
 *
 * @param select The revision to select after refresh, or null for no selection change.
 * @param vfsChanged Whether to refresh IntelliJ's VFS from disk. Set to true for operations
 *   that change working copy files (edit, new, abandon, rebase, squash, split, fetch).
 *
 * Use cases:
 * - `invalidate()` - just refresh, no selection change
 * - `invalidate(changeId)` - refresh and select specific change (e.g., after `jj edit`)
 * - `invalidate(WorkingCopy)` - refresh and select working copy (e.g., after `jj new`)
 * - `invalidate(bookmark)` - refresh and select a bookmark
 */
fun JujutsuRepository.invalidate(select: Revision? = null, vfsChanged: Boolean = false) {
    if (vfsChanged) {
        VfsUtil.markDirtyAndRefresh(true, true, true, directory)
    }
    val stateModel = project.stateModel
    stateModel.repositoryStates.invalidate()
    stateModel.logRefresh.notify(Unit)
    if (select != null) {
        stateModel.changeSelection.notify(ChangeKey(this, select))
    }
}
