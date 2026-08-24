package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.vcs.VcsRootChecker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepositoryHealth
import java.io.File

/**
 * Checks if a directory is a jujutsu repository root
 */
class JujutsuRootChecker : VcsRootChecker() {
    override fun getSupportedVcs() = JujutsuVcsBase.getKey()

    override fun isRoot(path: VirtualFile) = isJujutsuRoot(path)

    // Only a directory-existence check ("does .jj exist"), same as isRoot — jj itself might still
    // be unable to open it (broken/stale store, moved repo). We don't probe that here since
    // validateRoot runs synchronously on the Directory Mappings settings panel; instead this
    // consults JujutsuRepositoryHealth, populated by JujutsuStateModel's own background reads
    // (jj-idea-9ife), so a repo already known to be unreadable shows up red in that table.
    override fun validateRoot(file: VirtualFile) = !JujutsuRepositoryHealth.isUnreadable(file.path)

    override fun isVcsDir(dirName: String) = dirName == JujutsuVcsBase.DOT_JJ

    companion object {
        /**
         * Check if the given directory is a jujutsu repository root.
         * Uses file system directly to avoid VFS cache staleness issues.
         */
        fun isJujutsuRoot(dir: VirtualFile) = File(dir.path, JujutsuVcsBase.DOT_JJ).isDirectory

        /**
         * Search upward from the given directory to find the JJ repository root.
         * Returns the directory containing the .jj directory, or null if not found.
         */
        fun findJujutsuRoot(startDir: VirtualFile?): VirtualFile? {
            var currentDir = startDir
            while (currentDir != null) {
                if (isJujutsuRoot(currentDir)) return currentDir
                currentDir = currentDir.parent
            }
            return null
        }

        /**
         * Search upward from the given path to find the JJ repository root.
         * Returns the directory containing the .jj directory, or null if not found.
         */
        fun findJujutsuRoot(startPath: String?): VirtualFile? = startPath
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?.let { findJujutsuRoot(it) }
    }
}
