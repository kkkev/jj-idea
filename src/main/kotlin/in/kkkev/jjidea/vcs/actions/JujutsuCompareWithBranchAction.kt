package `in`.kkkev.jjidea.vcs.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.vcs.isJujutsu
import `in`.kkkev.jjidea.vcs.jujutsuRepository

/**
 * Action to compare current file with a bookmark, change, or revision
 */
class JujutsuCompareWithBranchAction : DumbAwareAction(
    JujutsuBundle.message("action.compare.branch"),
    JujutsuBundle.message("action.compare.branch.description"),
    AllIcons.Actions.Diff
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.file ?: return
        val jujutsuRoot = file.jujutsuRepository

        // JujutsuCompareWithPopup.show() already handles EDT scheduling internally
        JujutsuCompareWithPopup.show(project, jujutsuRoot) { chosen ->
            showDiffWithRevision(project, file, chosen, jujutsuRoot)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = (e.project?.isJujutsu ?: false) && (e.file != null)
    }

    private fun showDiffWithRevision(
        project: Project,
        file: VirtualFile,
        revision: Revision,
        repo: JujutsuRepository
    ) {
        val filePath = repo.getRelativePath(file)

        // Load content in background to avoid EDT blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            // Get file content at target revision
            val revisionResult = repo.commandExecutor.show(filePath, revision)
            val revisionContent = if (revisionResult.isSuccess) revisionResult.stdout else ""

            // Show diff on EDT
            ApplicationManager.getApplication().invokeLater {
                val contentFactory = DiffContentFactory.getInstance()
                val diffManager = DiffManager.getInstance()

                // Create diff content
                val content1 = contentFactory.create(project, revisionContent)
                val content2 = if (file.exists()) {
                    contentFactory.create(project, file)
                } else {
                    contentFactory.createEmpty()
                }

                val diffRequest = SimpleDiffRequest(
                    JujutsuBundle.message("diff.title.compare", file.name, revision.toString()),
                    content1,
                    content2,
                    revision.toString(),
                    JujutsuBundle.message("diff.label.current")
                )

                diffManager.showDiff(project, diffRequest)
            }
        }
    }
}
