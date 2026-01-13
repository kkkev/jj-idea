package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.actions.SelectedBlockHistoryAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * Action to show history filtered to selected lines in the editor
 *
 * Delegates to the platform's built-in SelectedBlockHistoryAction which handles
 * line-range history display using the VCS provider's history implementation.
 */
class JujutsuShowHistoryForSelectionAction : DumbAwareAction(
    JujutsuBundle.message("action.show.history.selection"),
    JujutsuBundle.message("action.show.history.selection.description"),
    AllIcons.Vcs.History
) {
    private val log = Logger.getInstance(javaClass)

    private val platformAction = SelectedBlockHistoryAction()

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        e.editor?.let {
            e.file?.let { file ->
                log.info("Showing history for selection in ${file.path}")
                platformAction.actionPerformed(e)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.editor
        val project = e.project

        e.presentation.isEnabledAndVisible = (project != null) &&
                (editor != null) &&
                (e.file != null) &&
                editor.selectionModel.hasSelection() &&
                project.isJujutsu
    }
}
