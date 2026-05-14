package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber

private val log = Logger.getInstance("in.kkkev.jjidea.vcs.VcsExtensions")

/**
 * Find JujutsuVcs for a project. Returns null if not found.
 * Use when VCS might not be available (e.g., general actions that could run in any context).
 */
val Project.possibleJujutsuVcs
    get() = ProjectLevelVcsManager.getInstance(this).findVcsByName(JujutsuVcs.VCS_NAME) as? JujutsuVcs

val Project?.isJujutsu get() = this?.stateModel?.isJujutsu == true

val Project.initialisedJujutsuRepositories: Collection<JujutsuRepository>
    get() = stateModel.initialisedRepositories.value.values

fun Project.jujutsuRepositoryForRoot(directory: VirtualFile) = stateModel.initialisedRepositories.value[directory]

fun Project.possibleJujutsuRepositoryFor(file: VirtualFile): JujutsuRepository? =
    file.getUserData(JujutsuDataKeys.VIRTUAL_FILE_LOG_ENTRY)?.repo ?: possibleJujutsuRepositoryFor(file.filePath)

/**
 * Gets the log entry for the specified virtual file. If the virtual file originated from a file change in a historical
 * commit, this is the log entry of that historical commit. Otherwise it is the working copy of the file's repository.
 */
fun Project.possibleLogEntryFor(file: VirtualFile): LogEntry? =
    file.getUserData(JujutsuDataKeys.VIRTUAL_FILE_LOG_ENTRY)
        ?: possibleJujutsuRepositoryFor(file)?.workingCopy

fun Project.jujutsuRepositoryFor(file: VirtualFile) = possibleJujutsuRepositoryFor(file)
    ?: throw VcsException(JujutsuBundle.getMessage("vcs.error.no.root", file))

fun Project.possibleJujutsuRepositoryFor(filePath: FilePath) =
    VcsUtil.getVcsRootFor(this, filePath)?.let { jujutsuRepositoryForRoot(it) }

fun Project.jujutsuRepositoryFor(filePath: FilePath) = possibleJujutsuRepositoryFor(filePath)
    ?: throw VcsException(JujutsuBundle.message("vcs.error.no.root", filePath))

fun Project.possibleVirtualFileFor(fileAtVersion: FileAtVersion): VirtualFile? {
    val filePath = fileAtVersion.filePath
    return possibleJujutsuRepositoryFor(filePath)?.getVirtualFile(fileAtVersion) ?: filePath.virtualFile
}

val VirtualFile.filePath get() = VcsUtil.getFilePath(this)
fun VirtualFile.pathRelativeTo(root: VirtualFile) = path.removePrefix(root.path).removePrefix("/")
fun VirtualFile.pathRelativeTo(root: String) = path.removePrefix(root).removePrefix("/")
fun VirtualFile.getChildPath(relativePath: String, isDirectory: Boolean = false) =
    VcsUtil.getFilePath(this.path + "/" + relativePath, isDirectory)

val VirtualFile.contentLocator get() = (this as? JujutsuVirtualFile)?.contentLocator ?: WorkingCopy
val VirtualFile.fileAtVersion get() = FileAtVersion(filePath, contentLocator)

/**
 * Pre-emptively loads and caches file contents. Useful to call from a background thread if we know that the virtual
 * file is subsequently going to have its contents inspected by a foreground thread.
 */
fun VirtualFile.cacheContents() {
    if (isDirectory) {
        log.warn("cacheContents called on directory: $path")
        return
    }
    contentsToByteArray(true)
}

fun FilePath.relativeTo(root: VirtualFile) = path.removePrefix(root.path).removePrefix("/")

/**
 * File path associated with the change. For an update, the only file path, for a rename, the target, for a delete, the
 * old file path. This is useful for finding the path of a file on which to act, given a change.
 */
val Change.filePath
    get() = this.afterRevision?.file ?: this.beforeRevision?.file ?: throw VcsException("Change $this has no file")

val ContentRevision.locator: ContentLocator
    get() {
        val revisionNumber = this.revisionNumber
        return when {
            revisionNumber is JujutsuRevisionNumber -> revisionNumber.contentLocator
            this is CurrentContentRevision -> WorkingCopy
            else -> throw VcsException("Not a Jujutsu revision: $this")
        }
    }
