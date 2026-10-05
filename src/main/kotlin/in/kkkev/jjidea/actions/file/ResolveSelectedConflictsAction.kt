package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.change.openMergeTool
import `in`.kkkev.jjidea.actions.change.resolveConflictsInEditor
import `in`.kkkev.jjidea.actions.change.resolveSelectedAvailability
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.vcs.filterInJujutsuRepo
import `in`.kkkev.jjidea.vcs.possibleJujutsuVcs

/**
 * Scopes conflict resolution to an explicit changes-tree selection, when there is one.
 *
 * Returns `null` when [changes] is empty, meaning the caller had no file-level selection at
 * all (e.g. invoked from a commit whose own diff is empty, or from a context with no changes
 * tree) - the caller should then fall back to a broader lookup (inherited conflicts, or the
 * single focused file).
 *
 * Returns a (possibly empty) list when [changes] is non-empty: an explicit selection is always
 * honored exactly, even if none of the selected files are conflicted. Falling through to a
 * broader lookup in that case would wrongly surface "Resolve Conflicts…" for a selected
 * non-conflicted file just because some unrelated file elsewhere in the repo is conflicted.
 */
internal fun scopeToConflicted(changes: List<FileChange>): List<FileChange>? =
    changes.takeIf { it.isNotEmpty() }?.filter { it.isConflicted }

/** Default: open the first selected conflicted file in the editor (design doc S6, jj-idea-z9tp). */
class ResolveSelectedConflictsAction : SelectedConflictsAction(
    "action.resolve.selected.conflicts",
    ::resolveConflictsInEditor
)

/** Secondary: the modal three-way merge tool for the same selection. */
class OpenMergeToolForSelectedConflictsAction : SelectedConflictsAction(
    "action.open.merge.tool.selected",
    ::openMergeTool
)

abstract class SelectedConflictsAction(
    private val bundleKey: String,
    private val perform: (Project, List<VirtualFile>) -> Unit
) : DumbAwareAction(
        JujutsuBundle.message(bundleKey),
        JujutsuBundle.message("$bundleKey.description"),
        null
    ) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        perform(project, conflictedFilesFromContext(e))
    }

    override fun update(e: AnActionEvent) {
        val hasConflicts = e.project?.possibleJujutsuVcs != null && conflictedFilesFromContext(e).isNotEmpty()
        val logEntry = e.logEntry
        val availability = resolveSelectedAvailability(
            hasContextConflicts = hasConflicts,
            isWorkingCopyContext = logEntry == null || logEntry.isWorkingCopy
        )
        e.presentation.isVisible = availability.visible
        e.presentation.isEnabled = availability.enabled
        if (availability.needsEditHint) {
            e.presentation.text = JujutsuBundle.message("$bundleKey.needsEdit")
            e.presentation.description = JujutsuBundle.message("action.resolve.conflicts.needsEdit.description")
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun conflictedFilesFromContext(e: AnActionEvent): List<VirtualFile> {
        scopeToConflicted(e.changes)?.let { conflicted -> return conflicted.mapNotNull { it.filePath.virtualFile } }

        val project = e.project ?: return emptyList()
        val changeListManager = ChangeListManager.getInstance(project)
        return (
            if (e.logEntry?.isWorkingCopy == true) {
                // Working copy log entry with inherited conflicts: the entry's own changes tree is empty
                // because jj diff --summary shows 0 changes, but conflicts propagated from the parent are
                // still materialised on disk and tracked by ChangeListManager.
                changeListManager.allChanges.filterInJujutsuRepo(project)
            } else {
                // Editor / project view: fall back to the single focused file
                listOfNotNull(e.file?.let { file -> changeListManager.getChange(file) })
            }
        ).filter { it.fileStatus == FileStatus.MERGED_WITH_CONFLICTS }
            .mapNotNull { it.virtualFile }
    }
}
