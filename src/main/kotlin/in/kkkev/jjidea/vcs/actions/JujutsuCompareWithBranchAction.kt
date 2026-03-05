package `in`.kkkev.jjidea.vcs.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.vcs.filePath
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
        val file = e.file ?: return
        val repo = file.jujutsuRepository

        // JujutsuCompareWithPopup.show() already handles EDT scheduling internally
        RevisionSelectorPopup.show(repo, true) { chosen ->
            showDiffWithRevision(repo, file, chosen)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = (e.project?.isJujutsu ?: false) && (e.file != null)
    }

    private fun showDiffWithRevision(repo: JujutsuRepository, file: VirtualFile, revision: Revision) {
        repo.commandExecutor.createCommand {
            show(file.filePath, revision)
        }.onSuccess {
            showDiffContent(repo, file, revision, it)
        }.onFailure {
            showDiffContent(repo, file, revision, "")
        }.executeAsync()
    }

    fun showDiffContent(repo: JujutsuRepository, file: VirtualFile, revision: Revision, revisionContent: String) {
        val contentFactory = DiffContentFactory.getInstance()
        val diffManager = DiffManager.getInstance()

        // Create diff content
        val content1 = contentFactory.create(repo.project, revisionContent)
        val content2 = if (file.exists()) {
            contentFactory.create(repo.project, file)
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

        diffManager.showDiff(repo.project, diffRequest)
    }
}
