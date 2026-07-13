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
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.CommonProcessors
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.notifiableState
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.simpleNotifier
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.JujutsuVcs.Companion.DOT_JJ
import `in`.kkkev.jjidea.vcs.ignore.JujutsuIgnoreService
import `in`.kkkev.jjidea.vcs.ignore.JujutsuIgnoredFilesService
import `in`.kkkev.jjidea.vcs.ignore.JujutsuTrackedFilesService
import `in`.kkkev.jjidea.vcs.pathRelativeTo
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central state model for Jujutsu VCS data.
 *
 * ## Event flow
 *
 * ```
 * VCS_CONFIGURATION_CHANGED ─┐
 * VCS_ACTIVATED ─────────────┤
 * (initial bootstrap) ───────┴─→ jujutsuVcsRoots.invalidate()
 *                                      │
 * VFS (.jj created/deleted) ───────────┴─→ initialisedRepositories.invalidate()
 *                                                  │
 *                                                  ├─→ watchOpHeads()
 *                                                  ├─→ references / workingCopies / gitRemotes .invalidate()
 *                                                  ├─→ logRefresh.notify()
 *                                                  └─→ ToolWindowEnabler (connects separately)
 *
 * VFS (working-copy file / .gitignore /  ─→ 300ms debounce ─→ scheduleRepositoryRefresh():
 *      external jj op via op_heads)                            references / workingCopies .invalidate()
 *                                                              + logRefresh.notify()
 *
 * jj became available ──────────────────→ references / workingCopies .invalidate() + logRefresh.notify()
 *
 * repo.invalidate(select) ──────────────→ logCache.clear()
 *   (VCS operations)                       + references / workingCopies .invalidate()
 *                                          + logRefresh.notify()  (+ changeSelection.notify() if select)
 *
 * workingCopies changed ────────────────→ VcsDirtyScopeManager.dirDirtyRecursively() (changed repos only)
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
 * 1. Constructor registers all subscriptions (VFS, VCS config/activation, jj availability)
 * 2. Explicit `jujutsuVcsRoots.invalidate()` + `initialisedRepositories.invalidate()` bootstrap the
 *    first scan (roots → repositories cascade)
 * 3. initialisedRepositories change cascades to watchOpHeads + references/workingCopies/gitRemotes
 *    + logRefresh
 * 4. ToolWindowEnabler connects to initialisedRepositories separately
 *
 * Note: [SimpleNotifiableState] does NOT auto-invalidate on construction.
 * Callers must call [invalidate] explicitly to trigger the first load.
 */
@Service(Service.Level.PROJECT)
class JujutsuStateModel(private val project: Project) : Disposable {
    private val log = Logger.getInstance(javaClass)

    /** Counter for [suppressRefresh]/[resumeRefresh]. When > 0, file-change refreshes are suppressed. */
    private val refreshSuppression = AtomicInteger(0)

    /** Watch requests for each repo's operation-heads directory, replaced whenever the repo set changes. */
    private var opHeadsWatchRequests: Set<LocalFileSystem.WatchRequest> = emptySet()

    /**
     * Cache of JJ VCS root directories, regardless of whether they have been initialised.
     */
    val jujutsuVcsRoots = notifiableState(project, "Jujutsu VCS Root Directories", emptySet()) {
        ProjectLevelVcsManager.getInstance(project).findVcsByName(JujutsuVcs.VCS_NAME)
            ?.let { ProjectLevelVcsManager.getInstance(project).getDirectoryMappings(it) }
            ?.mapNotNull {
                (it.directory.takeIf { it.isNotEmpty() } ?: project.basePath)
                    ?.let { directory -> VfsUtil.findFile(Path.of(directory), true) }
            }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * Set of VCS-configured JJ repositories that are actually initialised (have .jj directory).
     * Cached to avoid EDT slow operations. Updated via file listener when .jj directories change.
     */
    val initialisedRepositories =
        notifiableState<Map<VirtualFile, JujutsuRepository>>(project, "Jujutsu Initialized Repositories", emptyMap()) {
            val allRootPaths = jujutsuVcsRoots.immediateValue
            val rootsWithUniqueNames = allRootPaths.distinctBy { it.name }.toSet()

            // This runs on background thread - safe to call VCS manager
            allRootPaths.associateWith {
                val displayName = if (it in rootsWithUniqueNames) {
                    it.name
                } else {
                    it.pathRelativeTo(project.basePath!!)
                }
                JujutsuRepositoryImpl(project, it, displayName)
            }.filterValues {
                val initialised = it.isInitialised
                if (!initialised) {
                    JujutsuNotifications.notifyUninitializedRoot(project, it)
                }
                initialised
            }
        }

    /**
     * Whether this project has any initialised JJ repositories.
     * Safe to call from EDT - uses cached value.
     */
    val isJujutsu: Boolean get() = initialisedRepositories.value.isNotEmpty()

    /**
     * Bookmarks and tags ([RepositoryReferences]) for each initialised repository. Invalidated on
     * every VCS operation and debounced file-change refresh so consumers always see fresh data
     * without owning any loading logic themselves.
     *
     * Bookmarks and tags share a single state because they are always loaded and invalidated
     * together; keeping them separate only duplicated every invalidation and subscription.
     */
    val references = notifiableState<Map<JujutsuRepository, RepositoryReferences>>(
        project,
        "Jujutsu References",
        emptyMap()
    ) {
        initialisedRepositories.immediateValue.values.associateWith { repo ->
            RepositoryReferences(
                bookmarks = repo.logService.getBookmarks().getOrNull().orEmpty(),
                tags = repo.logService.getTags().getOrNull().orEmpty()
            )
        }
    }

    /**
     * Working copy log entries - one for each repo.
     */
    val workingCopies = notifiableState(
        project,
        "Working Copies",
        emptyMap(),
        equalityCheck = { a, b ->
            a.mapValues { it.value.stateKey } == b.mapValues { it.value.stateKey }
        }
    ) {
        initialisedRepositories.immediateValue
            .map { (_, it) -> it.logCache[WorkingCopy] }
            .associateBy { it.repo.directory.path }
    }

    /**
     * Git remotes for each initialised repository, keyed by [VirtualFile.path].
     *
     * Safe to read from BGT via [JujutsuRepository.gitRemotes] (uses [NotifiableState.immediateValue]).
     * For EDT callers or notification-driven updates, read [value] or [connect] here directly.
     */
    val gitRemotes = notifiableState(project, "Jujutsu Git Remotes", emptyMap()) {
        initialisedRepositories.immediateValue.values.associate { repo ->
            val result = repo.commandExecutor.gitRemoteList()
            val remotes = if (!result.isSuccess) {
                emptyList()
            } else {
                result.stdout.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { line ->
                        val name = line.substringBefore(' ')
                        val url = line.substringAfter(' ', "").trim()
                        if (url.isEmpty()) null else GitRemote(name, url)
                    }
            }
            repo.directory.path to remotes
        }
    }

    /**
     * Log refresh notifier. Fires when the log should reload, either because:
     * - A VCS operation completed (via [JujutsuRepository.invalidate])
     * - Working copy state changed (cascaded from [workingCopies])
     *
     * The log subscribes to this instead of [workingCopies] directly, because
     * [workingCopies] only tracks the working copy entry and won't fire for
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
            references.invalidate()
            workingCopies.invalidate()
            logRefresh.notify(Unit)
        }, 300)
    }

    init {
        // Fire off an initial invalidation of repository roots to transition from empty to the actual roots - so that
        // initial state is initialised in all listeners
        jujutsuVcsRoots.invalidate()
        initialisedRepositories.invalidate()

        val connection = project.messageBus.connect(this)

        // Watch for file changes to mark files dirty and detect .jj directory changes
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    // Check for .jj directory creation/deletion to update initialisedRepositories
                    if (events.any { isJjDirectoryEvent(it) }) {
                        initialisedRepositories.invalidate()
                    }

                    // Mark changed files as dirty for VCS refresh
                    // Uses cached initialisedRepositories to avoid EDT slow operations
                    val repos = initialisedRepositories.value.values
                    if (repos.isEmpty()) return

                    val dirtyScopeManager = VcsDirtyScopeManager.getInstance(project)
                    val dirtyFiles = mutableListOf<VirtualFile>()
                    events.forEach { event ->
                        when (event) {
                            is VFileContentChangeEvent, is VFileCreateEvent, is VFileDeleteEvent -> {
                                event.file?.let { file ->
                                    if (repos.any { VfsUtil.isAncestor(it.directory, file, false) } &&
                                        !isUnderJjDirectory(file, repos)
                                    ) {
                                        dirtyFiles.add(file)
                                    }
                                }
                            }
                        }
                    }
                    if (dirtyFiles.isNotEmpty()) {
                        dirtyScopeManager.filesDirty(dirtyFiles, null)
                        // Also forward to the async ignored-files holder so it can do an incremental re-check
                        val ignoredFilesService = JujutsuIgnoredFilesService.getInstance(project)
                        val trackedFilesService = JujutsuTrackedFilesService.getInstance(project)
                        dirtyFiles
                            .groupBy { file -> repos.firstOrNull { VfsUtil.isAncestor(it.directory, file, false) } }
                            .forEach { (repo, files) ->
                                if (repo != null) {
                                    val filePaths = files.map { VcsUtil.getFilePath(it) }
                                    ignoredFilesService.markDirty(repo, filePaths)
                                    // An edited/created/deleted file's tracked status can change too.
                                    trackedFilesService.invalidatePaths(repo, filePaths)
                                }
                            }
                    }
                    var hasRepoChanges = dirtyFiles.isNotEmpty()

                    // When .gitignore or .git/info/exclude changes, invalidate the ignore cache
                    // and mark the repo dirty recursively so getChanges re-enumerates ignored files.
                    val ignoreService = JujutsuIgnoreService.getInstance(project)
                    for (event in events) {
                        if (event !is VFileContentChangeEvent &&
                            event !is VFileCreateEvent &&
                            event !is VFileDeleteEvent
                        ) {
                            continue
                        }
                        val file = event.file ?: continue
                        val isGitignore = file.name == ".gitignore" ||
                            file.path.endsWith(".git/info/exclude")
                        if (!isGitignore) continue
                        val repo = repos.firstOrNull { VfsUtil.isAncestor(it.directory, file, true) }
                            ?: continue
                        ignoreService.invalidate(repo)
                        JujutsuIgnoredFilesService.getInstance(project).invalidate(repo)
                        dirtyScopeManager.dirDirtyRecursively(repo.directory)
                        hasRepoChanges = true
                    }

                    // An external jj operation (e.g. terminal `jj bookmark create`) rewrites the
                    // operation head under .jj/repo/op_heads/. We refresh on it but never mark .jj/ files dirty.
                    val hasExternalJjOp = events.any { it.file?.let { f -> isOpHeadsChange(f, repos) } == true }

                    if (hasRepoChanges || hasExternalJjOp) {
                        if (refreshSuppression.get() > 0) {
                            log.info("File changes detected but refresh suppressed, skipping")
                            return
                        }
                        log.info(
                            "File changes detected (${events.size} events on " +
                                "${Thread.currentThread().name}), scheduling workingCopies invalidation"
                        )
                        scheduleRepositoryRefresh()
                    }
                }

                private fun isUnderJjDirectory(file: VirtualFile, repos: Collection<JujutsuRepository>) =
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
        // reload repository states and log. Without this, workingCopies stays empty from the
        // failed load when jj was unavailable, and the working copy panel shows "No repositories".
        JjAvailabilityChecker.getInstance(project).status.connect(this) { status ->
            if (status is JjAvailabilityStatus.Available) {
                log.info("jj became available (${status.version}), refreshing state")
                references.invalidate()
                workingCopies.invalidate()
                logRefresh.notify(Unit)
            }
        }

        jujutsuVcsRoots.connect(this) { _ ->
            initialisedRepositories.invalidate()
        }

        // Subscribe to VCS configuration changes
        connection.subscribe(
            ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
            VcsListener {
                log.debug("VCS configuration changed")
                jujutsuVcsRoots.invalidate()
            }
        )
        connection.subscribe(
            ProjectLevelVcsManagerEx.VCS_ACTIVATED,
            VcsActivationListener {
                log.debug("VCS activated")
                jujutsuVcsRoots.invalidate()
            }
        )

        // The repository set changed: re-aim the op-heads watch and refresh every state that
        // derives from it (bookmarks, tags, working copies, git remotes), then reload the log.
        initialisedRepositories.connect(this) { new ->
            log.info("Initialized roots changed to ${new.size} roots")
            watchOpHeads(new.values)
            references.invalidate()
            workingCopies.invalidate()
            gitRemotes.invalidate()
            logRefresh.notify(Unit)
            // Trigger a full ignored-file scan for each newly discovered repo
            val ignoredFilesService = JujutsuIgnoredFilesService.getInstance(project)
            val trackedFilesService = JujutsuTrackedFilesService.getInstance(project)
            new.values.forEach { repo ->
                ignoredFilesService.invalidate(repo)
                trackedFilesService.invalidate(repo)
            }
        }

        // When repository states change (VCS operations completed), mark directories dirty
        // to trigger ChangeProvider refresh for file changes.
        // Only dirty repos whose state actually changed to avoid flooding FileStatusManager.
        var previousStateKeys = emptyMap<String, LogEntry.StateKey>()
        workingCopies.connect(this) { new ->
            val newKeys = new.mapValues { it.value.stateKey }
            val changedEntries = new.filter { entry -> previousStateKeys[entry.key] != entry.value.stateKey }
            previousStateKeys = newKeys
            if (changedEntries.isNotEmpty()) {
                log.info("Repository states changed for ${changedEntries.size}/${new.size} repos, marking dirty")
                val dirtyScopeManager = VcsDirtyScopeManager.getInstance(project)
                changedEntries.forEach { entry ->
                    dirtyScopeManager.dirDirtyRecursively(entry.value.repo.directory)
                }
            }
        }
    }

    /**
     * Watch each repo's `.jj/repo/op_heads/` directory so external jj operations (e.g.
     * `jj bookmark create` in a terminal) trigger a refresh. jj rewrites the operation head on
     * every operation, making this the canonical "something changed" signal — analogous to
     * git4idea watching `.git/HEAD`.
     *
     * ## Why this narrow path and not a broader one
     *
     * Our refresh signal is "the VFS delivered an event under a path we materialized." Because
     * `.jj/` is in the platform's ignored-files list (see [in.kkkev.jjidea.JujutsuFileTypeSetup]), the VFS does not
     * auto-discover it; we must add an explicit watch root AND force the subtree into the VFS via
     * [VfsUtilCore.processFilesRecursively] (as git4idea does for `.git`) before events flow. That
     * materialization cost scales with the subtree, so the watched path must be *small*.
     *
     * `op_heads/` is the only tiny directory in `.jj/repo/` — it holds a single operation-head file
     * (~4 KB). Its siblings are large and must never be pulled into the VFS:
     * `op_store/` (operation log), `index/`, and `store/` (the object store) are each tens of
     * megabytes and can exceed 100 MB combined on a busy repo. Watching `.jj/repo/` or `.jj/`
     * would force all of that into the VFS at startup.
     *
     * We watch the `op_heads` *directory* (not the `op_heads/heads/<op-id>` leaf): the head file is
     * named by operation id and is replaced — different name — on every operation, so watching the
     * containing directory is both correct and robust to jj reshuffling the layout beneath it,
     * while staying just as cheap.
     */
    private fun watchOpHeads(repos: Collection<JujutsuRepository>) {
        val fs = LocalFileSystem.getInstance()
        fs.removeWatchedRoots(opHeadsWatchRequests)
        val paths = repos.map { "${it.directory.path}/$OP_HEADS_RELATIVE_PATH" }.toSet()
        opHeadsWatchRequests = fs.addRootsToWatch(paths, true)
        runInBackground {
            paths.forEach { path ->
                fs.refreshAndFindFileByPath(path)?.let {
                    VfsUtilCore.processFilesRecursively(it, CommonProcessors.alwaysTrue())
                }
            }
        }
    }

    private fun isOpHeadsChange(file: VirtualFile, repos: Collection<JujutsuRepository>) =
        repos.any { VfsUtil.isAncestor(it.directory, file, false) } &&
            file.path.contains("/$OP_HEADS_RELATIVE_PATH")

    override fun dispose() {
        LocalFileSystem.getInstance().removeWatchedRoots(opHeadsWatchRequests)
    }

    companion object {
        /** Operation-heads directory, relative to a repo root. See [watchOpHeads] for why this exact path. */
        private const val OP_HEADS_RELATIVE_PATH = "$DOT_JJ/repo/op_heads"
    }
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
    logCache.clear()
    val stateModel = project.stateModel
    stateModel.references.invalidate()
    stateModel.workingCopies.invalidate()
    stateModel.logRefresh.notify(Unit)
    if (select != null) {
        stateModel.changeSelection.notify(ChangeKey(this, select))
    }
}
