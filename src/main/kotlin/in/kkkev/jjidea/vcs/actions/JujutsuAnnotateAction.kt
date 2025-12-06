package `in`.kkkev.jjidea.vcs.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.actions.AnnotateToggleAction
import `in`.kkkev.jjidea.vcs.JujutsuVcs

/**
 * Action to toggle Jujutsu annotations in the editor
 */
class JujutsuAnnotateAction : DumbAwareAction(
    "Annotate",
    "Annotate the current file with Jujutsu changes",
    AllIcons.Actions.Annotate
) {
    private val log = Logger.getInstance(JujutsuAnnotateAction::class.java)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { file ->
            log.info("Triggering annotation toggle for file: ${file.path}")

            // Delegate to the built-in AnnotateToggleAction
            ANNOTATE_TOGGLE_ACTION.actionPerformed(e)
        }
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        // Enable if we have a project, file, and it's under Jujutsu VCS
        e.presentation.isEnabledAndVisible = file?.let(JujutsuVcs::find)?.annotationProvider != null
    }

}

private val ANNOTATE_TOGGLE_ACTION = AnnotateToggleAction()
