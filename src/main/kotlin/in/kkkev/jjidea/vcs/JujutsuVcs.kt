package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vcs.*
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.jj.cli.CliLogService
import `in`.kkkev.jjidea.vcs.annotate.JujutsuAnnotationProvider
import `in`.kkkev.jjidea.vcs.changes.JujutsuChangeProvider
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber
import `in`.kkkev.jjidea.vcs.checkin.JujutsuCheckinEnvironment
import `in`.kkkev.jjidea.vcs.diff.JujutsuDiffProvider
import `in`.kkkev.jjidea.vcs.history.JujutsuHistoryProvider

/**
 * Main VCS implementation for Jujutsu
 */
class JujutsuVcs(project: Project) : AbstractVcs(project, VCS_NAME) {
    private val log = Logger.getInstance(JujutsuVcs::class.java)

    val commandExecutor: CommandExecutor by lazy { CliExecutor(root) }
    val logService: LogService by lazy { CliLogService(commandExecutor) }
    private val _changeProvider by lazy { JujutsuChangeProvider(this) }
    private val _diffProvider by lazy { JujutsuDiffProvider(this) }
    private val _checkinEnvironment by lazy { JujutsuCheckinEnvironment(this) }
    private val _historyProvider by lazy { JujutsuHistoryProvider(this) }
    private val _annotationProvider by lazy { JujutsuAnnotationProvider(myProject, this) }

    private val fileListener = object : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            val dirtyScopeManager = VcsDirtyScopeManager.getInstance(myProject)

            events.forEach { event ->
                when (event) {
                    is VFileContentChangeEvent, is VFileCreateEvent, is VFileDeleteEvent -> {
                        event.file?.let { file ->
                            if (VfsUtil.isAncestor(root, file, false)) {
                                dirtyScopeManager.fileDirty(file)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun getChangeProvider() = _changeProvider
    override fun getDiffProvider() = _diffProvider
    override fun getCheckinEnvironment() = _checkinEnvironment
    override fun getVcsHistoryProvider() = _historyProvider
    override fun getAnnotationProvider() = _annotationProvider

    override fun getConfigurable(): Configurable? {
        // TODO: Add configuration UI if needed
        return null
    }

    override fun getDisplayName(): String = VCS_DISPLAY_NAME

    override fun activate() {
        log.info("Jujutsu VCS activated for project: ${myProject.name}")
        super.activate()
        myProject.messageBus.connect(myProject).subscribe(VirtualFileManager.VFS_CHANGES, fileListener)
        log.debug("File listener registered for auto-refresh")
    }

    override fun deactivate() {
        log.info("Jujutsu VCS deactivated for project: ${myProject.name}")
        super.deactivate()
    }

    val root: VirtualFile by lazy {
        // Start from project base directory
        var currentDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        var foundRoot: VirtualFile? = null

        // Search upwards for .jj directory
        while (currentDir != null) {
            if (currentDir.findChild(".jj") != null) {
                foundRoot = currentDir
                break
            }
            currentDir = currentDir.parent
        }

        if (foundRoot == null) {
            throw VcsException("Project is not within a JJ repository (couldn't find a .jj directory above ${project.basePath}")
        }

        log.info("Jujutsu root for project ${project.name}: $foundRoot")

        foundRoot
    }

    fun createRevision(filePath: FilePath, revision: Revision) = JujutsuContentRevision(filePath, revision)

    fun getRelativePath(filePath: FilePath): String {
        val absolutePath = filePath.path
        val rootPath = root.path
        return  if (absolutePath.startsWith(rootPath)) {
            absolutePath.removePrefix(rootPath).removePrefix("/")
        } else {
            // Fall back to just the file name if path doesn't start with root
            filePath.name
        }
    }

    /**
     * Represents the content of a file at a specific jujutsu revision
     */
    inner class JujutsuContentRevision(private val filePath: FilePath, private val revision: Revision) : ContentRevision {
        override fun getContent(): String? {
            val result = commandExecutor.show(getRelativePath(filePath), revision)
            return if (result.isSuccess) result.stdout else null
        }

        override fun getFile() = filePath

        override fun getRevisionNumber() = JujutsuRevisionNumber(revision)
    }

    companion object {
        const val VCS_NAME = "Jujutsu"
        const val VCS_DISPLAY_NAME = "Jujutsu"

        private val KEY = createKey(VCS_NAME)

        fun getKey(): VcsKey = KEY

        fun find(project: Project?) = project?.let { project ->
            ProjectLevelVcsManager.getInstance(project)
                .getVcsFor(
                    project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                ) as? JujutsuVcs
        }

        fun find(root: VirtualFile) = ProjectLocator.getInstance()
            .guessProjectForFile(root)
            ?.let(ProjectLevelVcsManager::getInstance)
            ?.getVcsFor(root) as? JujutsuVcs

        fun findRequired(root: VirtualFile) = find(root) ?: throw VcsException("Jujutsu VCS not available for $root")
    }
}
