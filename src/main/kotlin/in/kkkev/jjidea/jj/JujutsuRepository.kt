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

// TODO Should map to a VCS root allocated to Jujutsu
// TODO Figure out what to do for an uninitialised root (probably do something special here)
/**
 * A (possible) JJ repository. "Possible" because the directory could be uninitialised, as this class allows
 * repository initialisation as well as access to all JJ actions.
 */
data class JujutsuRepository(val project: Project, val directory: VirtualFile) {
    val commandExecutor: CommandExecutor by lazy {
        val settings = JujutsuSettings.getInstance(project)
        CliExecutor(directory, settings.state.jjExecutablePath)
    }
    val logService: LogService by lazy { CliLogService(this) }

    /**
     * Path of this root, relative to the project directory.
     */
    val relativePath get() = project.guessProjectDir()?.let { directory.pathRelativeTo(it) } ?: directory.path
    val isInitialised get() = JujutsuRootChecker.isJujutsuRoot(directory)

    override fun toString() = "Repository:$relativePath"

    /**
     * Gets the path for the specified file path, relative to this root.
     */
    fun getRelativePath(filePath: FilePath): String {
        val absolutePath = filePath.path
        val rootPath = directory.path
        return if (absolutePath.startsWith(rootPath)) {
            absolutePath.removePrefix(rootPath).removePrefix("/")
        } else {
            // Fall back to just the file name if path doesn't start with root
            filePath.name
        }
    }

    fun getRelativePath(file: VirtualFile) = getRelativePath(VcsUtil.getFilePath(file))

    /**
     * Turns a path relative to this root into a FilePath.
     */
    fun getPath(relativePath: String, isDirectory: Boolean = false) =
        VcsUtil.getFilePath(directory.path + "/" + relativePath, isDirectory)

    fun createRevision(filePath: FilePath, revision: Revision): ContentRevision =
        JujutsuContentRevision(filePath, revision)

    /**
     * Represents the content of a file at a specific jujutsu revision
     */
    private inner class JujutsuContentRevision(private val filePath: FilePath, private val revision: Revision) :
        ContentRevision {
        override fun getContent(): String? {
            val result = commandExecutor.show(getRelativePath(filePath), revision)
            return result.stdout.takeIf { result.isSuccess }
        }

        override fun getFile() = filePath

        override fun getRevisionNumber() = JujutsuRevisionNumber(revision)
    }
}
