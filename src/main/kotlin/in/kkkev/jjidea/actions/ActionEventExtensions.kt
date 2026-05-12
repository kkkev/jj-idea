package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.history.JujutsuFileRevision
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import `in`.kkkev.jjidea.vcs.possibleLogEntryFor
import `in`.kkkev.jjidea.vcs.possibleVirtualFileFor

val AnActionEvent.file: VirtualFile? get() = this.getData(CommonDataKeys.VIRTUAL_FILE)

val AnActionEvent.logEntry: LogEntry? get() = this.getData(JujutsuDataKeys.LOG_ENTRY)

/** Gets the log entry from the DataSink, falling back to the file's user data. Use in actions that need to work in both changes tree and editor contexts. */
val AnActionEvent.logEntryForFile: LogEntry?
    get() = logEntry ?: project?.let { p -> file?.let(p::possibleLogEntryFor) }
val AnActionEvent.files: List<VirtualFile>
    get() = this.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
        ?: changes.mapNotNull {
            project?.let { p -> it.after?.let(p::possibleVirtualFileFor) }
        }
val AnActionEvent.filePaths: List<FilePath>
    get() = this.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()?.map { it.filePath }
        ?: changes.mapNotNull { it.after?.filePath }
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
