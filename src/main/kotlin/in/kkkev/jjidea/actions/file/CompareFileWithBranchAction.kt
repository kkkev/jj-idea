package `in`.kkkev.jjidea.actions.file

import com.intellij.diff.DiffManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.files
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.actions.singleRepoForFiles
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.diffRequest
import `in`.kkkev.jjidea.jj.fileAt
import `in`.kkkev.jjidea.ui.components.RevisionSelectorPopup
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.fileAtVersion
import `in`.kkkev.jjidea.vcs.filePath

/**
 * Action to compare current file with a bookmark, change, or revision
 */
class CompareFileWithBranchAction : DumbAwareAction(
    JujutsuBundle.message("action.compare.branch"),
    JujutsuBundle.message("action.compare.branch.description"),
    AllIcons.Actions.Diff
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val files = e.files
        val repo = e.singleRepoForFiles ?: return

        if (!files.isEmpty()) {
            RevisionSelectorPopup.show(
                "action.compare.branch.popup.title",
                repo,
                RevisionSelectorPopup.Filter(true, true)
            ) { chosen ->
                showDiff(repo, files, chosen)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.repoForFile != null
    }

    fun showDiff(repo: JujutsuRepository, files: List<VirtualFile>, leftRevision: Revision) {
        runInBackground {
            val diffManager = DiffManager.getInstance()

            // Locate change id in case revision is a bookmark or other expression
            val leftChangeId = repo.getLogEntry(leftRevision).id

            val diffRequests = files.map { file ->
                val leftSide = repo.createDiffSideFor(file.filePath.fileAt(leftChangeId))
                val rightSide = repo.createDiffSideFor(file.fileAtVersion)
                diffRequest(
                    JujutsuBundle.message("diff.title.compare", file.filePath.name, leftRevision.toString()),
                    leftSide,
                    rightSide
                )
            }

            runLater {
                diffRequests.forEach {
                    diffManager.showDiff(repo.project, it)
                }
            }
        }
    }
}
