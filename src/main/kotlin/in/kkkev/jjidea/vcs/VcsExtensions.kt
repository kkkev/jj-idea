package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.JujutsuRepositoryImpl
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications

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

val Project?.isJujutsu get() = this?.stateModel?.isJujutsu == true

/** All VCS roots in the project that are managed by Jujutsu. Returns VcsRoot objects (not just paths) since callers often need the full root info. */
val Project.jujutsuRoots get() = ProjectLevelVcsManager.getInstance(this).allVcsRoots.filter { it.vcs is JujutsuVcs }

fun Project.jujutsuRepositoryForRoot(directory: VirtualFile) = JujutsuRepositoryImpl(this, directory)

fun Project.possibleJujutsuRepositoryFor(file: VirtualFile) =
    VcsUtil.getVcsRootFor(this, file)?.let { jujutsuRepositoryForRoot(it) }

fun Project.jujutsuRepositoryFor(file: VirtualFile) = possibleJujutsuRepositoryFor(file)
    ?: throw VcsException(JujutsuBundle.getMessage("vcs.error.no.root", file))

fun Project.possibleJujutsuRepositoryFor(filePath: FilePath) =
    VcsUtil.getVcsRootFor(this, filePath)?.let { jujutsuRepositoryForRoot(it) }

fun Project.jujutsuRepositoryFor(filePath: FilePath) = possibleJujutsuRepositoryFor(filePath)
    ?: throw VcsException(JujutsuBundle.message("vcs.error.no.root", filePath))

/**
 * Get the Jujutsu repository for the specified directory, warning with a popup (and returning null) if the repository
 * is not yet initialised.
 */
fun Project.possibleInitialisedJujutsuRepositoryForRoot(directory: VirtualFile): JujutsuRepositoryImpl? {
    val repo = jujutsuRepositoryForRoot(directory)
    return if (repo.isInitialised)
        repo
    else {
        JujutsuNotifications.notifyUninitializedRoot(this, repo)
        null
    }
}

/** JujutsuRepository instances for all Jujutsu roots in the project. Use this when you need to work with JJ commands. */
val Project.jujutsuRepositories get() = jujutsuRoots.map { this.jujutsuRepositoryForRoot(it.path) }
val Project.initialisedJujutsuRepositories: Set<JujutsuRepository> get() = stateModel.initializedRoots.value

val VirtualFile.filePath get() = VcsUtil.getFilePath(this)
fun VirtualFile.pathRelativeTo(root: VirtualFile) = path.removePrefix(root.path).removePrefix("/")
fun VirtualFile.getChildPath(relativePath: String, isDirectory: Boolean = false) =
    VcsUtil.getFilePath(this.path + "/" + relativePath, isDirectory)

fun FilePath.relativeTo(root: VirtualFile) = path.removePrefix(root.path).removePrefix("/")

/**
 * File path associated with the change. For an update, the only file path, for a rename, the target, for a delete, the
 * old file path. This is useful for finding the path of a file on which to act, given a change.
 */
val Change.filePath get() = this.afterRevision?.file ?: this.beforeRevision?.file
