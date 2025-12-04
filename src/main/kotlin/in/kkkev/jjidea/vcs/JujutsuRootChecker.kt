package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.vcs.VcsRootChecker
import com.intellij.openapi.vfs.VirtualFile

/**
 * Checks if a directory is a jujutsu repository root
 */
class JujutsuRootChecker : VcsRootChecker() {
    override fun getSupportedVcs() = JujutsuVcs.getKey()

    override fun isRoot(path: VirtualFile) = isJujutsuRoot(path)

    override fun isVcsDir(dirName: String) = dirName == ".jj"

    companion object {
        /**
         * Check if the given directory is a jujutsu repository root
         */
        fun isJujutsuRoot(dir: VirtualFile) = dir.findChild(".jj")?.isDirectory ?: false
    }
}
