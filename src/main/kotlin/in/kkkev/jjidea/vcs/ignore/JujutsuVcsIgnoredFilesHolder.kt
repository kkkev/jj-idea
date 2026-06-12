package `in`.kkkev.jjidea.vcs.ignore

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.FileHolder
import com.intellij.openapi.vcs.changes.VcsDirtyScope
import com.intellij.openapi.vcs.changes.VcsManagedFilesHolder
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.vcs.JujutsuVcs

/**
 * EP facade for [JujutsuIgnoredFilesService], registered at `com.intellij.vcs.ignoredFilesHolder`.
 *
 * When this EP is present, the platform no longer asks [JujutsuChangeProvider] to report ignored
 * files; instead the holder loads them asynchronously and notifies via [VcsManagedFilesHolder.TOPIC].
 * This is the same design git4idea uses via `GitIgnoredFilesHolder`.
 *
 * The holder itself is stateless — it delegates everything to the project-level
 * [JujutsuIgnoredFilesService] which owns the per-repo scan state.
 */
class JujutsuVcsIgnoredFilesHolder private constructor(private val project: Project) : VcsManagedFilesHolder {
    private val service get() = project.service<JujutsuIgnoredFilesService>()

    override fun isInUpdatingMode() = service.isInUpdatingMode()
    override fun containsFile(file: FilePath, vcsRoot: VirtualFile) = service.containsFile(file, vcsRoot)
    override fun values() = service.values()

    // Mutating operations are no-ops: the holder is driven by JujutsuStateModel events, not the CLM.
    override fun addFile(file: FilePath) {}
    override fun cleanAll() {}
    override fun cleanUnderScope(scope: VcsDirtyScope) {}
    override fun copy(): FileHolder = this

    class Provider(private val project: Project) : VcsManagedFilesHolder.Provider {
        override fun getVcs(): AbstractVcs =
            ProjectLevelVcsManager.getInstance(project).findVcsByName(JujutsuVcs.VCS_NAME)!!

        override fun createHolder() = JujutsuVcsIgnoredFilesHolder(project)
    }
}
