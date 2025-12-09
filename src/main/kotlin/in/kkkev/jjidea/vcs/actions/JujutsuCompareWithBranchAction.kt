package `in`.kkkev.jjidea.vcs.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.RevisionExpression
import `in`.kkkev.jjidea.vcs.JujutsuVcs

/**
 * Action to compare current file with a bookmark, change, or revision
 */
class JujutsuCompareWithBranchAction : DumbAwareAction(
    "Compare with Branch or Change...",
    "Compare current file with a bookmark, change ID, or revision",
    null
) {
    private val log = Logger.getInstance(JujutsuCompareWithBranchAction::class.java)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        // Show popup to select revision/bookmark
        // TODO What does this take? A hash? change id? bookmark? what combination?
        JujutsuCompareWithPopup.show(e.project!!, e.vcs!!) { chosen ->
            showDiffWithRevision(e.project!!, e.file!!, RevisionExpression(chosen), e.vcs!!)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = (e.project != null) && (e.file != null) && (e.vcs != null)
    }

    private fun showDiffWithRevision(project: Project, file: VirtualFile, revision: Revision, vcs: JujutsuVcs) {
        val filePath = vcs.getRelativePath(com.intellij.vcsUtil.VcsUtil.getFilePath(file))

        // Load content in background to avoid EDT blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            // Get file content at target revision
            val revisionResult = vcs.commandExecutor.show(filePath, revision)
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
                    "Compare ${file.name} with $revision",
                    content1,
                    content2,
                    // TODO Is this the correct title?
                    revision.toString(),
                    // TODO Should this be "working copy"? @?
                    // TODO Localise this
                    "Current"
                )

                diffManager.showDiff(project, diffRequest)
            }
        }
    }
}
