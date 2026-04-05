package `in`.kkkev.jjidea.actions.file

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.editor
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.actions.performAction
import `in`.kkkev.jjidea.actions.repoForFile

/**
 * Action to show history filtered to selected lines in the editor
 *
 * Delegates to the platform's built-in Vcs.ShowHistoryForBlock action which handles
 * line-range history display using the VCS provider's history implementation.
 */
class ShowFileHistoryForSelectionAction :
    DumbAwareAction(
        JujutsuBundle.message("action.show.history.selection"),
        JujutsuBundle.message("action.show.history.selection.description"),
        AllIcons.Vcs.History
    ) {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        if (e.editor != null) {
            val file = e.file ?: return

            log.info("Showing history for selection in ${file.path}")
            e.performAction("Vcs.ShowHistoryForBlock")
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.editor
        val project = e.project

        e.presentation.isEnabledAndVisible =
            editor != null && editor.selectionModel.hasSelection() && e.repoForFile != null
    }
}
