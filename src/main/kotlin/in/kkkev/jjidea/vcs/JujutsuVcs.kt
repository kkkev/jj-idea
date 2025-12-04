package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.util.IntellijInternalApi
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.vcs.changes.JujutsuChangeProvider
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber
import `in`.kkkev.jjidea.vcs.checkin.JujutsuCheckinEnvironment
import `in`.kkkev.jjidea.jj.cli.JujutsuCliExecutor
import `in`.kkkev.jjidea.jj.JujutsuCommandExecutor
import `in`.kkkev.jjidea.vcs.diff.JujutsuDiffProvider
import `in`.kkkev.jjidea.vcs.history.JujutsuHistoryProvider
import `in`.kkkev.jjidea.jj.cli.JujutsuCliLogService
import `in`.kkkev.jjidea.jj.JujutsuLogService

/**
 * Main VCS implementation for Jujutsu
 */
class JujutsuVcs(project: Project) : AbstractVcs(project, VCS_NAME) {

    private val log = Logger.getInstance(JujutsuVcs::class.java)

    val commandExecutor: JujutsuCommandExecutor by lazy {
        root?.let { JujutsuCliExecutor(it) } ?: throw IllegalStateException("Jujutsu repository root not found")
    }
    val logService: JujutsuLogService by lazy { JujutsuCliLogService(commandExecutor) }
    private val _changeProvider by lazy { JujutsuChangeProvider(this) }
    private val _diffProvider by lazy { JujutsuDiffProvider(myProject, this) }
    private val _checkinEnvironment by lazy { JujutsuCheckinEnvironment(this) }
    private val _historyProvider by lazy { JujutsuHistoryProvider(this) }

    override fun getChangeProvider() = _changeProvider

    override fun getDiffProvider() = _diffProvider

    override fun getCheckinEnvironment() = _checkinEnvironment

    override fun getVcsHistoryProvider() = _historyProvider

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
        val basePath = project.basePath ?: return@lazy null
        var currentDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return@lazy null
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

        @OptIn(IntellijInternalApi::class)
        private val KEY = VcsKey(VCS_NAME)

        fun getKey() = KEY

        fun find(project: Project): JujutsuVcs? {
            val basePath = project.basePath ?: return null
            val root = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null
            return ProjectLevelVcsManager.getInstance(project).getVcsFor(root) as? JujutsuVcs
        }

        fun find(root: VirtualFile) = ProjectLocator.getInstance().guessProjectForFile(root)?.let { find(it) }

        fun findRequired(root: VirtualFile) = find(root) ?: throw VcsException("Jujutsu VCS not available for $root")
    }
}
