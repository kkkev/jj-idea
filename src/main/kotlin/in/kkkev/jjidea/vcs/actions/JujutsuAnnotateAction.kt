package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.actions.AnnotateToggleAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * Action to toggle Jujutsu annotations in the editor
 */
class JujutsuAnnotateAction : DumbAwareAction(
    JujutsuBundle.message("action.annotate"),
    JujutsuBundle.message("action.annotate.description"),
    null
) {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        e.file?.let { file ->
            log.info("Triggering annotation toggle for file: ${file.path}")

            // Delegate to the built-in AnnotateToggleAction
            ANNOTATE_TOGGLE_ACTION.actionPerformed(e)
        }
    }

    override fun update(e: AnActionEvent) {
        // Enable if we have a project, file, and it's under Jujutsu VCS
        e.presentation.isEnabledAndVisible = e.file?.isJujutsu ?: false
    }

}

private val ANNOTATE_TOGGLE_ACTION = AnnotateToggleAction()
