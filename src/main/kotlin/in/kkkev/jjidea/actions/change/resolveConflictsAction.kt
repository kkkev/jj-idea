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

fun resolveConflictsAction(project: Project, entry: LogEntry?): DumbAwareAction =
    object : DumbAwareAction(
        JujutsuBundle.message("action.resolve.conflicts"),
        JujutsuBundle.message("action.resolve.conflicts.description"),
        null
    ) {
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
                e.presentation.text = JujutsuBundle.message("action.resolve.conflicts.needsEdit")
                e.presentation.description = JujutsuBundle.message("action.resolve.conflicts.needsEdit.description")
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            resolveConflicts(project, conflictedFiles(project, entry))
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }
