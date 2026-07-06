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
import `in`.kkkev.jjidea.vcs.possibleJujutsuVcs

// Read live rather than capturing once at construction time: a stale snapshot could hand
// the merge dialog a file that jj has already resolved (e.g. resolved externally, or in a
// prior invocation of this same action instance), which throws in JujutsuMergeProvider
// (jj-idea-3cvb).
private fun conflictedFiles(project: Project, entry: LogEntry?): List<VirtualFile> =
    if (entry?.isWorkingCopy == true) {
        ChangeListManager.getInstance(project).allChanges
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
            e.presentation.isEnabled = conflictedFiles(project, entry).isNotEmpty()
            e.presentation.isVisible = entry?.isWorkingCopy == true
        }

        override fun actionPerformed(e: AnActionEvent) {
            val mergeProvider = project.possibleJujutsuVcs?.mergeProvider ?: return
            val files = conflictedFiles(project, entry)
            if (files.isEmpty()) return
            AbstractVcsHelper.getInstance(project).showMergeDialog(files, mergeProvider)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }
