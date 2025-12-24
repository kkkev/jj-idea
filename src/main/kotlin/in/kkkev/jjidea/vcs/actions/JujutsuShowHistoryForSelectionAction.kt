package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.actions.SelectedBlockHistoryAction
import `in`.kkkev.jjidea.JujutsuBundle

/**
 * Action to show history filtered to selected lines in the editor
 *
 * Delegates to the platform's built-in SelectedBlockHistoryAction which handles
 * line-range history display using the VCS provider's history implementation.
 */
class JujutsuShowHistoryForSelectionAction : DumbAwareAction(
    JujutsuBundle.message("action.show.history.selection"),
    JujutsuBundle.message("action.show.history.selection.description"),
    null
) {
    private val log = Logger.getInstance(JujutsuShowHistoryForSelectionAction::class.java)

    private val platformAction = SelectedBlockHistoryAction()

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(CommonDataKeys.EDITOR)?.let { editor ->
            e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { file ->
                log.info("Showing history for selection in ${file.path}")
                platformAction.actionPerformed(e)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.editor

        e.presentation.isEnabledAndVisible = (e.project != null) &&
                (editor != null) &&
                (e.file != null) &&
                editor.selectionModel.hasSelection() &&
                (e.vcs != null)
    }
}
