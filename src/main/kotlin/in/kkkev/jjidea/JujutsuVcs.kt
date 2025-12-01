package `in`.kkkev.jjidea

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.changes.JujutsuChangeProvider
import `in`.kkkev.jjidea.changes.JujutsuRevisionNumber
import `in`.kkkev.jjidea.checkin.JujutsuCheckinEnvironment
import `in`.kkkev.jjidea.commands.JujutsuCliExecutor
import `in`.kkkev.jjidea.commands.JujutsuCommandExecutor
import `in`.kkkev.jjidea.diff.JujutsuDiffProvider

/**
 * Main VCS implementation for Jujutsu
 */
class JujutsuVcs(project: Project) : AbstractVcs(project, VCS_NAME) {

    private val log = Logger.getInstance(JujutsuVcs::class.java)

    val commandExecutor: JujutsuCommandExecutor = JujutsuCliExecutor(project.root)
    private val _changeProvider by lazy { JujutsuChangeProvider(this) }
    private val _diffProvider by lazy { JujutsuDiffProvider(myProject, this) }
    private val _checkinEnvironment by lazy { JujutsuCheckinEnvironment(this) }

    override fun getChangeProvider() = _changeProvider

    override fun getDiffProvider() = _diffProvider

    override fun getCheckinEnvironment() = _checkinEnvironment

    override fun getConfigurable(): Configurable? {
        // TODO: Add configuration UI if needed
        return null
    }

    override fun getDisplayName(): String = VCS_DISPLAY_NAME

    override fun activate() {
        log.info("Jujutsu VCS activated for project: ${myProject.name}")
        super.activate()
    }

    override fun deactivate() {
        log.info("Jujutsu VCS deactivated for project: ${myProject.name}")
        super.deactivate()
    }

    val root: VirtualFile? by lazy {
        // Start from project base directory
        var currentDir = project.root
        var foundRoot: VirtualFile? = null

        // Search upwards for .jj directory
        while (currentDir != null) {
            if (currentDir.findChild(".jj") != null) {
                foundRoot = currentDir
                break
            }
            currentDir = currentDir.parent
        }

        log.info("Jujutsu root for project ${project.name}: $foundRoot")

        foundRoot
    }

    fun createRevision(filePath: FilePath, revision: String) = JujutsuContentRevision(filePath, revision)

    /**
     * Represents the content of a file at a specific jujutsu revision
     */
    inner class JujutsuContentRevision(private val filePath: FilePath, private val revision: String) : ContentRevision {
        override fun getContent(): String? {
            val repoRoot = root ?: return null

            // Get relative path from repository root
            val absolutePath = filePath.path
            val rootPath = repoRoot.path
            val relativePath = if (absolutePath.startsWith(rootPath)) {
                absolutePath.removePrefix(rootPath).removePrefix("/")
            } else {
                // Fall back to just the file name if path doesn't start with root
                filePath.name
            }

            val result = commandExecutor.show(relativePath, revision)
            return if (result.isSuccess) result.stdout else null
        }

        override fun getFile() = filePath

        override fun getRevisionNumber() = JujutsuRevisionNumber(revision)
    }


    companion object {
        const val VCS_NAME = "Jujutsu"
        const val VCS_DISPLAY_NAME = "Jujutsu"

        fun find(project: Project) = ProjectLevelVcsManager.getInstance(project).getVcsFor(project.root) as? JujutsuVcs
    }
}

val Project.root get() = this.basePath!!.let { LocalFileSystem.getInstance().findFileByPath(it) }!!
