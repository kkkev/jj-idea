package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vcs.actions.AnnotateToggleAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * Action to toggle Jujutsu annotations in the editor.
 * This is a toggle action with a checkmark to indicate whether annotations are shown.
 * No icon is used - the checkmark takes that space.
 */
class JujutsuAnnotateAction :
    ToggleAction(
        JujutsuBundle.message("action.annotate"),
        JujutsuBundle.message("action.annotate.description"),
        null // No icon - the toggle checkmark uses this space
    ),
    DumbAware {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        // Delegate to the built-in AnnotateToggleAction to check if annotations are shown
        return ANNOTATE_TOGGLE_ACTION.isSelected(e)
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.file?.let { file ->
            log.info("Triggering annotation toggle for file: ${file.path}")

            // Delegate to the built-in AnnotateToggleAction
            ANNOTATE_TOGGLE_ACTION.actionPerformed(e)
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        // Enable if we have a project, file, and it's under Jujutsu VCS
        e.presentation.isEnabledAndVisible = e.file?.isJujutsu ?: false
    }
}

private val ANNOTATE_TOGGLE_ACTION = AnnotateToggleAction()
