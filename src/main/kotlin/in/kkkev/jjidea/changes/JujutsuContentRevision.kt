package `in`.kkkev.jjidea.changes

import `in`.kkkev.jjidea.JujutsuVcs
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber

/**
 * Represents the content of a file at a specific jujutsu revision
 */
class JujutsuContentRevision private constructor(
    private val filePath: FilePath,
    private val revision: String,
    private val project: Project,
    private val vcs: JujutsuVcs
) : ContentRevision {

    override fun getContent(): String? {
        // Find the repository root by looking for .jj directory
        val projectBaseDir = project.baseDir ?: return null

        // Start from project base directory
        var currentDir = projectBaseDir
        var foundRoot: com.intellij.openapi.vfs.VirtualFile? = null

        // Search upwards for .jj directory
        while (currentDir != null) {
            if (currentDir.findChild(".jj") != null) {
                foundRoot = currentDir
                break
            }
            currentDir = currentDir.parent
        }

        val repoRoot = foundRoot ?: return null

        // Get relative path from repository root
        val absolutePath = filePath.path
        val rootPath = repoRoot.path
        val relativePath = if (absolutePath.startsWith(rootPath)) {
            absolutePath.removePrefix(rootPath).removePrefix("/")
        } else {
            // Fall back to just the file name if path doesn't start with root
            filePath.name
        }

        val result = vcs.commandExecutor.show(repoRoot, relativePath, revision)
        return if (result.isSuccess) {
            result.stdout
        } else {
            null
        }
    }

    override fun getFile(): FilePath = filePath

    override fun getRevisionNumber(): VcsRevisionNumber {
        return JujutsuRevisionNumber(revision)
    }

    companion object {
        fun createRevision(
            filePath: FilePath,
            revision: String,
            project: Project,
            vcs: JujutsuVcs
        ): JujutsuContentRevision {
            return JujutsuContentRevision(filePath, revision, project, vcs)
        }
    }
}

/**
 * Simple revision number implementation for jujutsu
 */
class JujutsuRevisionNumber(private val revision: String) : VcsRevisionNumber {
    override fun asString(): String = revision

    override fun compareTo(other: VcsRevisionNumber?): Int {
        if (other !is JujutsuRevisionNumber) return 0
        return revision.compareTo(other.revision)
    }
}
