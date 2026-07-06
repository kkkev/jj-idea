package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresEdt
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.filterInJujutsuProject
import `in`.kkkev.jjidea.vcs.history.JujutsuFileRevision
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import `in`.kkkev.jjidea.vcs.possibleLogEntryFor
import `in`.kkkev.jjidea.vcs.possibleVirtualFileFor

val AnActionEvent.file: VirtualFile? get() = this.getData(CommonDataKeys.VIRTUAL_FILE)

val AnActionEvent.logEntry: LogEntry? get() = this.getData(JujutsuDataKeys.LOG_ENTRY)

/** Gets the log entry from the DataSink, falling back to the file's user data. Use in actions that need to work in both changes tree and editor contexts. */
val AnActionEvent.logEntryForFile: LogEntry?
    get() = logEntry ?: project?.let { p -> file?.let(p::possibleLogEntryFor) }

/**
 * Raw VIRTUAL_FILE_ARRAY captured cheaply on the EDT. Pass together with [changes] to
 * [Project.filesFor] or [Project.jujutsuFilesFor] in a background thread to resolve the
 * actual virtual files (that resolution may run `jj log` and must not happen on the EDT).
 */
@get:RequiresEdt
val AnActionEvent.fileList: List<VirtualFile>?
    get() = getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()

/**
 * Resolves the selected virtual files from a raw file list or from selected changes.
 *
 * When [fileList] is non-null it is returned as-is. Otherwise each change's `after`
 * [FileChange.FileAtVersion] is resolved via [possibleVirtualFileFor], which may run a
 * `jj log` subprocess. Capture [fileList] and [changes] on the EDT, then call this from
 * a `runInBackground` block.
 */
@RequiresBackgroundThread
fun Project.filesFor(fileList: List<VirtualFile>?, changes: List<FileChange>): List<VirtualFile> =
    fileList ?: changes.mapNotNull { it.after?.let(::possibleVirtualFileFor) }

/**
 * Background-safe replacement for the old `jujutsuFiles` event extension. Resolves files
 * via [filesFor] and filters to those in a Jujutsu-managed root, falling back to
 * [focusedFile] when the resolved list is empty.
 *
 * Capture [fileList], [changes], and [focusedFile] on the EDT, then call from a
 * `runInBackground` block.
 */
@RequiresBackgroundThread
fun Project.jujutsuFilesFor(
    fileList: List<VirtualFile>?,
    changes: List<FileChange>,
    focusedFile: VirtualFile?
): List<VirtualFile> =
    filesFor(fileList, changes).filterInJujutsuProject(this)
        .ifEmpty { listOfNotNull(focusedFile).filterInJujutsuProject(this) }

val AnActionEvent.filePaths: List<FilePath>
    get() = this.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()?.map { it.filePath }
        ?: changes.mapNotNull { it.after?.filePath }

/** Like [filePaths] but includes the source path of renames and the path of deletes. Use in restore operations. */
val AnActionEvent.restorePaths: List<FilePath>
    get() = this.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()?.map { it.filePath }
        ?: changes.flatMap { it.allPaths }.distinct()

val AnActionEvent.editor: Editor? get() = this.getData(CommonDataKeys.EDITOR)

val AnActionEvent.changes: List<FileChange>
    get() = (
        (this.getData(VcsDataKeys.SELECTED_CHANGES) ?: this.getData(VcsDataKeys.CHANGES))
            ?.toList()
            ?: emptyList()
    ).map { FileChange.from(it) }

val AnActionEvent.fileRevision get() = this.getData(VcsDataKeys.VCS_FILE_REVISION) as? JujutsuFileRevision

/**
 * Gets the Jujutsu repository for the single file selected in the action.
 */
val AnActionEvent.repoForFile: JujutsuRepository?
    get() = project?.let { p -> file?.let { p.possibleJujutsuRepositoryFor(it) } }

/**
 * Gets the single Jujutsu repository for all files selected in the action, or `null` if the files represent multiple
 * repositories
 */
val AnActionEvent.singleRepoForFiles: JujutsuRepository?
    get() = filePaths.map { project?.possibleJujutsuRepositoryFor(it) }.toSet().singleOrNull()

/**
 * Like [singleRepoForFiles] but uses [restorePaths], so it resolves a repository even when the selection
 * contains only deleted or renamed-source files (which have no after path).
 */
val AnActionEvent.singleRepoForRestore: JujutsuRepository?
    get() = restorePaths.mapNotNull { project?.possibleJujutsuRepositoryFor(it) }.toSet().singleOrNull()
