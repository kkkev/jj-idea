package `in`.kkkev.jjidea

import com.intellij.openapi.util.IntellijInternalApi
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.VcsRootChecker
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Checks if a directory is a jujutsu repository root
 */
class JujutsuRootChecker : VcsRootChecker() {
    // VcsKey constructor is internal API - using opt-in annotation
    @OptIn(IntellijInternalApi::class)
    override fun getSupportedVcs() = VcsKey(JujutsuVcs.VCS_NAME)

    override fun isRoot(path: VirtualFile) = isJujutsuRoot(path)

    override fun isVcsDir(dirName: String) = dirName == ".jj"

    companion object {
        /**
         * Check if the given directory is a jujutsu repository root
         */
        fun isJujutsuRoot(dir: VirtualFile) = dir.findChild(".jj")?.isDirectory ?: false
    }
}
