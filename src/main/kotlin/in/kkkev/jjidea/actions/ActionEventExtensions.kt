package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor

val AnActionEvent.file: VirtualFile? get() = this.getData(CommonDataKeys.VIRTUAL_FILE)

val AnActionEvent.logEntry: LogEntry? get() = this.getData(JujutsuDataKeys.LOG_ENTRY)
val AnActionEvent.files: List<VirtualFile>
    get() = this.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
        ?: changes.map { change -> change.afterRevision?.file ?: change.beforeRevision?.file }
            .mapNotNull { it?.virtualFile }
val AnActionEvent.editor: Editor? get() = this.getData(CommonDataKeys.EDITOR)

val AnActionEvent.changes: List<Change>
    get() = (this.getData(VcsDataKeys.SELECTED_CHANGES) ?: this.getData(VcsDataKeys.CHANGES))
        ?.toList()
        ?: emptyList()

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
    get() = files.map { project?.possibleJujutsuRepositoryFor(it) }.toSet().singleOrNull()
