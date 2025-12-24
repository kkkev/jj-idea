package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.ui.JujutsuCustomLogTabManager
import `in`.kkkev.jjidea.vcs.JujutsuVcs

/**
 * Action to open a custom Jujutsu log tab in the VCS log tool window.
 *
 * This provides full control over the log UI, allowing customizations not
 * possible with standard VCS log extension points.
 */
class OpenJujutsuLogTabAction : DumbAwareAction(JujutsuBundle.message("action.open.log")) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        JujutsuCustomLogTabManager.getInstance(project).openCustomLogTab()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        // Always visible in menus, but only enabled when in a Jujutsu project
        e.presentation.isVisible = true
        e.presentation.isEnabled = project != null && isJujutsuProject(project)
    }

    private fun isJujutsuProject(project: Project): Boolean =
        com.intellij.openapi.vcs.ProjectLevelVcsManager.getInstance(project)
            .allVcsRoots
            .any { it.vcs is JujutsuVcs }
}
