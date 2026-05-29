package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor

object JujutsuWidgetSupport {
    const val RECENT_ROOT_PROPERTY = "jujutsu.statusbar.recentRoot"
    private val log = Logger.getInstance(JujutsuWidgetSupport::class.java)

    fun rememberRecentRoot(project: Project, path: String) {
        PropertiesComponent.getInstance(project).setValue(RECENT_ROOT_PROPERTY, path)
    }

    fun currentRepository(project: Project, file: VirtualFile?): JujutsuRepository? {
        val repositories = project.initialisedJujutsuRepositories
            .sortedByDescending { it.directory.path.length }

        file?.let {
            project.possibleJujutsuRepositoryFor(it)?.also { repo ->
                log.debug("Widget currentRepository: vcs-root lookup → ${repo.directory.path}")
                rememberRecentRoot(project, repo.directory.path)
                return repo
            }

            repositories.firstOrNull { repo -> VfsUtilCore.isAncestor(repo.directory, it, false) }?.also { repo ->
                log.debug("Widget currentRepository: isAncestor fallback → ${repo.directory.path}")
                rememberRecentRoot(project, repo.directory.path)
                return repo
            }

            log.debug("Widget currentRepository: no jj repo found for file=${it.path}")
        }

        if (repositories.isEmpty()) return null

        val recentRoot = PropertiesComponent.getInstance(project).getValue(RECENT_ROOT_PROPERTY)
        val result = repositories.find { it.directory.path == recentRoot }
            ?: repositories.singleOrNull()
            ?: repositories.first()
        log.debug("Widget currentRepository: recentRoot fallback → ${result.directory.path}")
        return result
    }
}
