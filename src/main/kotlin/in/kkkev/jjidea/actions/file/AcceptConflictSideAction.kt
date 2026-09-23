package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vcs.merge.MergeSessionEx
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.merge.JujutsuMergeProvider
import `in`.kkkev.jjidea.vcs.possibleJujutsuVcs

/**
 * Bulk "Accept Yours"/"Accept Theirs" for an explicit multi-selection of conflicted files
 * (GitHub #66): lets a user resolve several files at once by side, in one gesture, instead of
 * stepping through them one at a time via the merge-tool queue
 * ([in.kkkev.jjidea.actions.change.resolveConflicts]).
 *
 * Deliberately scoped to the explicit selection only (unlike
 * [ResolveSelectedConflictsAction.conflictedFilesFromContext], which falls back to inherited
 * working-copy conflicts or the single focused file when there's no tree selection) - this is a
 * bulk "act on exactly what I picked" gesture, not a catch-all resolve entry point, so an empty
 * selection just hides the action rather than guessing a broader target.
 *
 * Working-copy only, matching [in.kkkev.jjidea.actions.change.resolveSelectedAvailability]'s
 * NEEDS_EDIT gating: resolving writes to disk, which only makes sense for `@`.
 */
sealed class AcceptConflictSideAction(private val resolution: MergeSession.Resolution) : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        acceptConflictSide(project, selectedConflictedFiles(e), resolution)
    }

    override fun update(e: AnActionEvent) {
        val logEntry = e.logEntry
        e.presentation.isEnabledAndVisible =
            e.project?.possibleJujutsuVcs != null &&
            (logEntry == null || logEntry.isWorkingCopy) &&
            selectedConflictedFiles(e).isNotEmpty()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun selectedConflictedFiles(e: AnActionEvent): List<VirtualFile> =
        scopeToConflicted(e.changes).orEmpty().mapNotNull { it.filePath.virtualFile }
}

class AcceptConflictYoursAction : AcceptConflictSideAction(MergeSession.Resolution.AcceptedYours)

class AcceptConflictTheirsAction : AcceptConflictSideAction(MergeSession.Resolution.AcceptedTheirs)

/**
 * Runs `jj resolve --tool` for each file via [in.kkkev.jjidea.vcs.merge.JujutsuMergeProvider]'s
 * [MergeSessionEx] - reusing its per-file `:ours`/`:theirs` orientation
 * (`ExtractedConflict.currentIsJjSide1`, GitHub #112) and modify/delete-safe write-back, the same
 * logic the platform's own `MultipleFileMergeDialog` buttons use - rather than duplicating it
 * here. Runs off the EDT since, unlike that dialog, this isn't invoked from inside a modal task.
 */
internal fun acceptConflictSide(
    project: Project,
    files: List<VirtualFile>,
    resolution: MergeSession.Resolution,
    mergeProviderFor: (Project) -> JujutsuMergeProvider? = { it.possibleJujutsuVcs?.mergeProvider },
    runInBackground: (() -> Unit) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) }
) {
    if (files.isEmpty()) return
    val mergeProvider = mergeProviderFor(project) ?: return
    runInBackground {
        val session = mergeProvider.createMergeSession(files) as MergeSessionEx
        session.acceptFilesRevisions(files, resolution)
        session.conflictResolvedForFiles(files, resolution)
    }
}
