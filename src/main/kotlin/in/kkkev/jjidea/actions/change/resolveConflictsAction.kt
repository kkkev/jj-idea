package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.LogEntry

// Read live rather than capturing once at construction time: see workingCopyConflicts's doc.
private fun conflictedFiles(project: Project, entry: LogEntry?): List<VirtualFile> =
    if (entry?.isWorkingCopy == true) workingCopyConflicts(project) else emptyList()

/** Default: open the first conflicted file in the editor (design doc S6, jj-idea-z9tp). */
fun resolveConflictsAction(project: Project, entry: LogEntry?): DumbAwareAction =
    ConflictLogAction(
        project,
        entry,
        "action.resolve.conflicts",
        "action.resolve.conflicts.description",
        ::resolveConflictsInEditor
    )

/** Secondary: the modal three-way merge tool for the same files. */
fun openMergeToolAction(project: Project, entry: LogEntry?): DumbAwareAction =
    ConflictLogAction(
        project,
        entry,
        "action.open.merge.tool",
        "action.open.merge.tool.description",
        ::openMergeTool
    )

private class ConflictLogAction(
    private val project: Project,
    private val entry: LogEntry?,
    textKey: String,
    descriptionKey: String,
    private val perform: (Project, List<VirtualFile>) -> Unit
) : DumbAwareAction(JujutsuBundle.message(textKey), JujutsuBundle.message(descriptionKey), null) {
    private val needsEditTextKey = "$textKey.needsEdit"

    override fun update(e: AnActionEvent) {
        val isWorkingCopy = entry?.isWorkingCopy == true
        val availability = resolveAvailability(
            isWorkingCopy = isWorkingCopy,
            hasConflict = entry?.hasConflict == true,
            workingCopyConflictCount = if (isWorkingCopy) conflictedFiles(project, entry).size else 0
        )
        e.presentation.isVisible = availability.visible
        e.presentation.isEnabled = availability.enabled
        if (availability.needsEditHint) {
            e.presentation.text = JujutsuBundle.message(needsEditTextKey)
            e.presentation.description = JujutsuBundle.message("action.resolve.conflicts.needsEdit.description")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        perform(project, conflictedFiles(project, entry))
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
