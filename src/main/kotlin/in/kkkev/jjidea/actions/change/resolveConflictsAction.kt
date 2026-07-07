package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.filterInJujutsuRepo
import `in`.kkkev.jjidea.vcs.possibleJujutsuVcs

// Read live rather than capturing once at construction time: a stale snapshot could hand
// the merge dialog a file that jj has already resolved (e.g. resolved externally, or in a
// prior invocation of this same action instance), which throws in JujutsuMergeProvider
// (jj-idea-3cvb).
private fun conflictedFiles(project: Project, entry: LogEntry?): List<VirtualFile> =
    if (entry?.isWorkingCopy == true) {
        ChangeListManager.getInstance(project).allChanges
            .filterInJujutsuRepo(project)
            .filter { it.fileStatus == FileStatus.MERGED_WITH_CONFLICTS }
            .mapNotNull { it.virtualFile }
    } else {
        emptyList()
    }

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
            val mergeProvider = project.possibleJujutsuVcs?.mergeProvider ?: return
            val files = conflictedFiles(project, entry)
            if (files.isEmpty()) return
            AbstractVcsHelper.getInstance(project).showMergeDialog(files, mergeProvider)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }
