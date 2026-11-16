package `in`.kkkev.jjidea.actions.top

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.ui.log.JujutsuCustomLogTabManager
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * Action to open a custom Jujutsu log tab in the VCS log tool window.
 *
 * This provides full control over the log UI, allowing customizations not
 * possible with standard VCS log extension points.
 */
class OpenJujutsuLogTabAction : DumbAwareAction(
    JujutsuBundle.message("action.open.log"),
    null,
    AllIcons.Vcs.History
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        JujutsuCustomLogTabManager.getInstance(project).openCustomLogTab(activate = true)
    }

    override fun update(e: AnActionEvent) {
        // Vcs.Operations.Popup.VcsAware is shared by every VCS, so only show this in a
        // Jujutsu project.
        e.presentation.isEnabledAndVisible = e.project.isJujutsu
    }
}
