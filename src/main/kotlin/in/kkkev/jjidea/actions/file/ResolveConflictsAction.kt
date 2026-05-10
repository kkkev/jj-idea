package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.vcs.possibleJujutsuVcs

class ResolveConflictsAction : DumbAwareAction(
    JujutsuBundle.message("action.resolve.conflicts"),
    JujutsuBundle.message("action.resolve.conflicts.description"),
    null
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val mergeProvider = project.possibleJujutsuVcs?.mergeProvider ?: return

        val conflictedFiles = ChangeListManager.getInstance(project)
            .allChanges
            .filter { it.fileStatus == FileStatus.MERGED_WITH_CONFLICTS }
            .mapNotNull { it.virtualFile }

        if (conflictedFiles.isEmpty()) return

        AbstractVcsHelper.getInstance(project).showMergeDialog(conflictedFiles, mergeProvider)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project?.possibleJujutsuVcs == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = ChangeListManager.getInstance(project)
            .allChanges
            .any { it.fileStatus == FileStatus.MERGED_WITH_CONFLICTS }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
