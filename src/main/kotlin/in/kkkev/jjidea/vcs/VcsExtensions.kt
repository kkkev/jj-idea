package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager.getInstance
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.LocalFileSystem.getInstance
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.SystemIndependent

/**
 * Find JujutsuVcs for a project. Returns null if not found.
 * Use when VCS might not be available (e.g., general actions that could run in any context).
 */
val Project.possibleJujutsuVcs
    get() = this?.let { project ->
        getInstance(this)
            .getVcsFor(
                this.basePath?.let<@SystemIndependent @NonNls String, VirtualFile?> { getInstance().findFileByPath(it) }
            ) as? JujutsuVcs
    }

/**
 * Find JujutsuVcs for a project, throwing if not found.
 * Use when VCS MUST be available (e.g., within Jujutsu-specific tool windows or providers).
 * @throws VcsException if Jujutsu VCS is not configured for this project
 */
// TODO Localise
val Project.jujutsuVcs
    get() = this.possibleJujutsuVcs ?: throw VcsException("Jujutsu VCS not available for project ${this.name}")

val Project.isJujutsu get() = this.possibleJujutsuVcs != null

val Project.jujutsuRoots get() = ProjectLevelVcsManager.getInstance(this).allVcsRoots.filter { it.vcs is JujutsuVcs }

/**
 * Find JujutsuVcs for a virtual file root. Returns null if not found.
 * Use when VCS might not be available.
 */
val VirtualFile.possibleJujutsuVcs
    get() = ProjectLocator.getInstance()
        .guessProjectForFile(this)
        ?.let(ProjectLevelVcsManager::getInstance)
        ?.getVcsFor(this) as? JujutsuVcs

/**
 * Find JujutsuVcs for a project, throwing if not found.
 * Use when VCS MUST be available (e.g., within Jujutsu-specific tool windows or providers).
 * @throws VcsException if Jujutsu VCS is not configured for this project
 */
val VirtualFile.jujutsuVcs
    get() = this.possibleJujutsuVcs
        ?: throw VcsException(JujutsuBundle.message("vcs.error.not.available", this.path))

val VirtualFile.isJujutsu get() = this.possibleJujutsuVcs != null
