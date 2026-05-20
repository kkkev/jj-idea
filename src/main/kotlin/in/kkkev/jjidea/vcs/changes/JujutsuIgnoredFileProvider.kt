package `in`.kkkev.jjidea.vcs.changes

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.IgnoredBeanFactory
import com.intellij.openapi.vcs.changes.IgnoredFileDescriptor
import com.intellij.openapi.vcs.changes.IgnoredFileProvider
import `in`.kkkev.jjidea.vcs.JujutsuVcs.Companion.DOT_JJ
import `in`.kkkev.jjidea.vcs.ignore.JujutsuIgnoreService
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor

/**
 * Tells the platform to treat `.jj/` internal directories and .gitignore-matched files as ignored.
 *
 * The `.jj/` exclusion prevents VcsDirtyScopeVfsListener feedback loops when JJ commands
 * modify internal files. The .gitignore check colors ignored files (build/, .gradle/, etc.)
 * correctly in the Project tool window and prevents them from entering the dirty scope.
 */
class JujutsuIgnoredFileProvider : IgnoredFileProvider {
    override fun isIgnoredFile(project: Project, filePath: FilePath): Boolean {
        val path = filePath.path
        if (path.contains("/$DOT_JJ/") || path.endsWith("/$DOT_JJ")) return true
        val repo = project.possibleJujutsuRepositoryFor(filePath) ?: return false
        return JujutsuIgnoreService.getInstance(project).isIgnored(filePath, repo.directory)
    }

    override fun getIgnoredFiles(project: Project): Set<IgnoredFileDescriptor> =
        project.initialisedJujutsuRepositories.map { repo ->
            IgnoredBeanFactory.ignoreUnderDirectory(repo.directory.path + "/$DOT_JJ", project)
        }.toSet()

    override fun getIgnoredGroupDescription() = "Jujutsu internal files"
}
