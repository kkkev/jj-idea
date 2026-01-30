package `in`.kkkev.jjidea.jj

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import `in`.kkkev.jjidea.jj.util.notifiableState
import `in`.kkkev.jjidea.jj.util.simpleNotifier
import `in`.kkkev.jjidea.ui.workingcopy.WorkingCopyToolWindowFactory
import `in`.kkkev.jjidea.vcs.jujutsuRepositories

/**
 * Central state model for Jujutsu VCS data.
 * Implements MVC pattern: this is the Model that views observe.
 *
 * Holds current VCS state and notifies observers via MessageBus when state changes.
 */
@Service(Service.Level.PROJECT)
class JujutsuStateModel(private val project: Project) {
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

    val repositoryStates = notifiableState(project, "Jujutsu Repository States", emptySet()) {
        project.jujutsuRepositories.mapNotNull {
            it.logService.getLog(WorkingCopy).getOrNull()?.firstOrNull()
        }.sortedBy {
            it.repo.relativePath
        }.toSet()
    }

    /**
     * Change selection notifier. Actions use this to request a specific change be selected.
     * Log panels and data loaders listen and select the matching entry if it belongs to their repo.
     */
    val changeSelection = simpleNotifier<ChangeKey>(project, "Jujutsu Change Selection")

    init {
        // Watch for file changes to mark files dirty and detect .jj directory changes
        project.messageBus.connect(project).subscribe(
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
                    events.forEach { event ->
                        when (event) {
                            is VFileContentChangeEvent, is VFileCreateEvent, is VFileDeleteEvent -> {
                                event.file?.let { file ->
                                    if (repos.any { VfsUtil.isAncestor(it.directory, file, false) }) {
                                        dirtyScopeManager.fileDirty(file)
                                    }
                                }
                            }
                        }
                    }
                }

                private fun isJjDirectoryEvent(event: VFileEvent): Boolean {
                    val name = event.file?.name ?: event.path.substringAfterLast('/')
                    return name == ".jj" && (event is VFileCreateEvent || event is VFileDeleteEvent)
                }
            }
        )

        // Update tool window availability when initializedRoots changes
        initializedRoots.connect(project) { _, new ->
            ToolWindowManager.getInstance(project)
                .getToolWindow(WorkingCopyToolWindowFactory.TOOL_WINDOW_ID)
                ?.isAvailable = new.isNotEmpty()
        }

        // When repository states change (VCS operations completed), mark directories dirty
        // to trigger ChangeProvider refresh for file changes
        repositoryStates.connect(project) { _, new ->
            if (new.isNotEmpty()) {
                val dirtyScopeManager = VcsDirtyScopeManager.getInstance(project)
                new.forEach { entry ->
                    dirtyScopeManager.dirDirtyRecursively(entry.repo.directory)
                }
            }
        }
    }
}

val Project.stateModel: JujutsuStateModel get() = service()

/**
 * Invalidate cached repository state and optionally request a change selection.
 *
 * @param select The revision to select after refresh, or null for no selection change.
 *
 * Use cases:
 * - `invalidate()` - just refresh, no selection change
 * - `invalidate(changeId)` - refresh and select specific change (e.g., after `jj edit`)
 * - `invalidate(WorkingCopy)` - refresh and select working copy (e.g., after `jj new`)
 * - `invalidate(bookmark)` - refresh and select a bookmark
 */
fun JujutsuRepository.invalidate(select: Revision? = null) {
    val stateModel = project.stateModel
    stateModel.repositoryStates.invalidate()
    if (select != null) {
        stateModel.changeSelection.notify(ChangeKey(this, select))
    }
}
