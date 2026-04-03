package `in`.kkkev.jjidea.vcs.changes

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.IgnoredBeanFactory
import com.intellij.openapi.vcs.changes.IgnoredFileDescriptor
import com.intellij.openapi.vcs.changes.IgnoredFileProvider
import `in`.kkkev.jjidea.vcs.JujutsuVcs.Companion.DOT_JJ
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories

/**
 * Tells the platform to treat `.jj/` internal directories as ignored.
 *
 * Without this, the platform's own `VcsDirtyScopeVfsListener` independently marks
 * `.jj/` files dirty when JJ commands modify them, triggering `ChangeProvider` →
 * more JJ commands → a feedback loop that starves editor repaints.
 */
class JujutsuIgnoredFileProvider : IgnoredFileProvider {
    override fun isIgnoredFile(project: Project, filePath: FilePath): Boolean {
        val path = filePath.path
        return path.contains("/$DOT_JJ/") || path.endsWith("/$DOT_JJ")
    }

    override fun getIgnoredFiles(project: Project): Set<IgnoredFileDescriptor> =
        project.initialisedJujutsuRepositories.map { repo ->
            IgnoredBeanFactory.ignoreUnderDirectory(repo.directory.path + "/$DOT_JJ", project)
        }.toSet()

    override fun getIgnoredGroupDescription() = "Jujutsu internal files"
}
