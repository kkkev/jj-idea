package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.stateModel

/**
 * Find JujutsuVcs for a project. Returns null if not found.
 * Use when VCS might not be available (e.g., general actions that could run in any context).
 */
val Project.possibleJujutsuVcs
    get() = ProjectLevelVcsManager.getInstance(this).findVcsByName(JujutsuVcs.VCS_NAME) as? JujutsuVcs

val Project?.isJujutsu get() = this?.stateModel?.isJujutsu == true

val Project.initialisedJujutsuRepositories: Collection<JujutsuRepository> get() = stateModel.initialisedRepositories.value.values

fun Project.jujutsuRepositoryForRoot(directory: VirtualFile) = stateModel.initialisedRepositories.value[directory]

fun Project.possibleJujutsuRepositoryFor(file: VirtualFile) =
    VcsUtil.getVcsRootFor(this, file)?.let { jujutsuRepositoryForRoot(it) }

fun Project.jujutsuRepositoryFor(file: VirtualFile) = possibleJujutsuRepositoryFor(file)
    ?: throw VcsException(JujutsuBundle.getMessage("vcs.error.no.root", file))

fun Project.possibleJujutsuRepositoryFor(filePath: FilePath) =
    VcsUtil.getVcsRootFor(this, filePath)?.let { jujutsuRepositoryForRoot(it) }

fun Project.jujutsuRepositoryFor(filePath: FilePath) = possibleJujutsuRepositoryFor(filePath)
    ?: throw VcsException(JujutsuBundle.message("vcs.error.no.root", filePath))

val VirtualFile.filePath get() = VcsUtil.getFilePath(this)
fun VirtualFile.pathRelativeTo(root: VirtualFile) = path.removePrefix(root.path).removePrefix("/")
fun VirtualFile.pathRelativeTo(root: String) = path.removePrefix(root).removePrefix("/")
fun VirtualFile.getChildPath(relativePath: String, isDirectory: Boolean = false) =
    VcsUtil.getFilePath(this.path + "/" + relativePath, isDirectory)

fun FilePath.relativeTo(root: VirtualFile) = path.removePrefix(root.path).removePrefix("/")

/**
 * File path associated with the change. For an update, the only file path, for a rename, the target, for a delete, the
 * old file path. This is useful for finding the path of a file on which to act, given a change.
 */
val Change.filePath get() = this.afterRevision?.file ?: this.beforeRevision?.file
