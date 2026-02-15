package `in`.kkkev.jjidea.jj

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.jj.cli.CliLogService
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.vcs.JujutsuRootChecker
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber
import `in`.kkkev.jjidea.vcs.pathRelativeTo

/**
 * A (possible) JJ repository. "Possible" because the directory could be uninitialised, as this class allows
 * repository initialisation as well as access to all JJ actions.
 */
interface JujutsuRepository {
    val project: Project
    val directory: VirtualFile
    val displayName: String
    val commandExecutor: CommandExecutor
    val logService: LogService
    val isInitialised: Boolean
    fun createRevision(filePath: FilePath, revision: Revision): ContentRevision
    fun getRelativePath(filePath: FilePath): String
    fun getRelativePath(file: VirtualFile): String
}

data class JujutsuRepositoryImpl(override val project: Project, override val directory: VirtualFile) :
    JujutsuRepository {
    private val executor: CommandExecutor by lazy {
        val settings = JujutsuSettings.getInstance(project)
        CliExecutor(directory, settings.state.jjExecutablePath)
    }

    /**
     * Command executor for initialized repositories. Throws if repository is not initialized.
     * Use [initExecutor] for initialization commands.
     */
    override val commandExecutor: CommandExecutor
        get() {
            requireInitialised()
            return executor
        }

    /**
     * Command executor for initialization operations (gitInit). Does not require repository to be initialized.
     */
    val initExecutor: CommandExecutor get() = executor

    override val logService: LogService by lazy { CliLogService(this) }

    private fun requireInitialised() {
        check(isInitialised) { "Repository at ${directory.path} is not initialized. Use initExecutor for gitInit." }
    }

    /**
     * Path of this root, relative to the project directory.
     */
    val relativePath get() = project.guessProjectDir()?.let { directory.pathRelativeTo(it) } ?: directory.path

    /**
     * Display name for UI. Shows the directory name, or "root" if it's the project root.
     */
    override val displayName get() = relativePath.ifEmpty { directory.name }

    override val isInitialised get() = JujutsuRootChecker.isJujutsuRoot(directory)

    override fun toString() = "Repository:$relativePath"

    /**
     * Gets the path for the specified file path, relative to this root.
     */
    override fun getRelativePath(filePath: FilePath): String {
        val absolutePath = filePath.path
        val rootPath = directory.path
        return if (absolutePath.startsWith(rootPath)) {
            absolutePath.removePrefix(rootPath).removePrefix("/")
        } else {
            // Fall back to just the file name if path doesn't start with root
            filePath.name
        }
    }

    override fun getRelativePath(file: VirtualFile) = getRelativePath(VcsUtil.getFilePath(file))

    override fun createRevision(filePath: FilePath, revision: Revision): ContentRevision =
        JujutsuContentRevision(filePath, revision)

    /**
     * Represents the content of a file at a specific jujutsu revision
     */
    private inner class JujutsuContentRevision(private val filePath: FilePath, private val revision: Revision) :
        ContentRevision {
        override fun getContent(): String? {
            val result = commandExecutor.show(filePath, revision)
            return result.stdout.takeIf { result.isSuccess }
        }

        override fun getFile() = filePath

        override fun getRevisionNumber() = JujutsuRevisionNumber(revision)
    }
}
