package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.ui.history.JujutsuFileHistoryTabManager
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepository

/**
 * Action to show custom file history for a Jujutsu-managed file.
 *
 * Opens a tab in the VCS tool window showing the file's commit history
 * with the same styling as the custom log view (but without the commit graph).
 */
class ShowJujutsuFileHistoryAction : DumbAwareAction(
    JujutsuBundle.message("history.action.show"),
    JujutsuBundle.message("history.action.show.description"),
    AllIcons.Vcs.History
) {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.file ?: return

        val filePath = VcsUtil.getFilePath(file)
        val repo = filePath.possibleJujutsuRepository ?: return

        log.info("Opening custom file history for: ${file.path}")

        JujutsuFileHistoryTabManager.getInstance(project).openFileHistory(filePath, repo)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.file

        // Only enable for files in a Jujutsu repository
        e.presentation.isEnabledAndVisible = project != null &&
            file != null &&
            !file.isDirectory &&
            VcsUtil.getFilePath(file).possibleJujutsuRepository != null
    }
}
