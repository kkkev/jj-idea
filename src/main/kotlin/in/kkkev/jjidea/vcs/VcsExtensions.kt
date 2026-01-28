package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.stateModel
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.SystemIndependent

/**
 * Find JujutsuVcs for a project. Returns null if not found.
 * Use when VCS might not be available (e.g., general actions that could run in any context).
 */
@Deprecated("doesn't work for multi-root projects")
val Project.possibleJujutsuVcs
    get() = ProjectLevelVcsManager.getInstance(this)
        .getVcsFor(
            this.basePath?.let {
                LocalFileSystem.getInstance().findFileByPath(it)
            }
        ) as? JujutsuVcs

/**
 * Find JujutsuVcs for a project, throwing if not found.
 * Use when VCS MUST be available (e.g., within Jujutsu-specific tool windows or providers).
 * @throws VcsException if Jujutsu VCS is not configured for this project
 *
 * TODO: Localise error message
 */
val Project.jujutsuVcs
    get() = this.possibleJujutsuVcs ?: throw VcsException("Jujutsu VCS not available for project ${this.name}")

val Project.isJujutsu get() = this.stateModel.isJujutsu

// TODO This is silly - why not just the paths?
val Project.jujutsuRoots get() = ProjectLevelVcsManager.getInstance(this).allVcsRoots.filter { it.vcs is JujutsuVcs }

fun Project.jujutsuRepositoryFor(directory: VirtualFile) = JujutsuRepository(this, directory)

// TODO Repositories? Roots? Which is it?
val Project.jujutsuRepositories get() = this.jujutsuRoots.map { this.jujutsuRepositoryFor(it.path) }

/**
 * Find JujutsuVcs for a virtual file root. Returns null if not found.
 * Use when VCS might not be available.
 */
val VirtualFile.possibleJujutsuVcs
    get() = ProjectLocator
        .getInstance()
        .guessProjectForFile(this)
        ?.let(ProjectLevelVcsManager::getInstance)
        ?.getVcsFor(this) as? JujutsuVcs

/**
 * Find JujutsuVcs for a project, throwing if not found.
 * Use when VCS MUST be available (e.g., within Jujutsu-specific tool windows or providers).
 * @throws VcsException if Jujutsu VCS is not configured for this project
 */
val VirtualFile.jujutsuVcs
    get() = this.possibleJujutsuVcs ?: throw VcsException(JujutsuBundle.message("vcs.error.not.available", this.path))

val VirtualFile.jujutsuRoot get() = jujutsuVcs.jujutsuRepositoryFor(this)
    ?: throw VcsException(JujutsuBundle.message("vcs.error.no.root", this.path))

val VirtualFile.isJujutsu get() = jujutsuVcs.jujutsuRepositoryFor(this) != null

val VirtualFile.jujutsuProject
    get() = ReadAction.compute<Project?, RuntimeException> {
        ProjectManager.getInstance().openProjects.firstOrNull { project ->
            project.jujutsuRoots.any { it.path == this }
        }
    } ?: throw VcsException("Cannot find Jujutsu VCS for file: ${this.path}")

fun VirtualFile.pathRelativeTo(root: VirtualFile) = path.removePrefix(root.path).removePrefix("/")

val FilePath.jujutsuRoot get() = this.virtualFile!!.jujutsuRoot
fun FilePath.relativeTo(root: VirtualFile) = path.removePrefix(root.path).removePrefix("/")
