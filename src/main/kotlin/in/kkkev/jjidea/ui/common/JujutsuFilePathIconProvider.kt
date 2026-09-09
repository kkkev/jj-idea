package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.FilePathIconProvider
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories

/**
 * Icon provider to use coloured repository icons to display folders in change trees that represent Jujutsu repository
 * roots.
 */
class JujutsuFilePathIconProvider : FilePathIconProvider {
    // On the 2025.1 floor this 2-arg overload is the interface's only (abstract) method; the 3-arg
    // getIcon(FilePath, Boolean, Project?) doesn't exist until 2025.2, where it is a default that
    // delegates here. Overriding only this one therefore compiles and behaves correctly across the
    // whole supported range. The platform marks it @Deprecated(forRemoval = true) in favour of the
    // 3-arg form; switch over once sinceBuild rises past 251. The isDirectory argument is
    // irrelevant here — this provider only matches a repository root by exact path equality.
    //
    // Scans the already-cached repo list directly (jj-idea-b65g) rather than going through
    // possibleJujutsuRepositoryFor, which resolves via VcsUtil.getVcsRootFor - a blocking read
    // action. This renderer is called from ChangesBrowserNodeRenderer on the EDT for every node
    // painted, and a read action there can park the EDT behind a concurrent write lock for the
    // write's whole duration (observed as a 58s freeze in this exact call chain, jj-idea-b65g).
    // Compares raw path strings rather than constructing/comparing FilePath (as the old
    // possibleJujutsuRepositoryFor-based check did) - repos are already matched by directory.path
    // everywhere else in the plugin (JujutsuRepositoryHealth, workingCopies), and it skips
    // FilePath's own platform-service dependency for what is already an exact-match check.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getIcon(filePath: FilePath, project: Project?) = project
        ?.initialisedJujutsuRepositories
        ?.firstOrNull { it.directory.path == filePath.path }
        ?.let { repo -> RepositoryIcons[repo] }
}
