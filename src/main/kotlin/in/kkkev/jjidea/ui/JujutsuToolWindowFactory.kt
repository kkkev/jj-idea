package `in`.kkkev.jjidea.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import `in`.kkkev.jjidea.vcs.JujutsuRootChecker

/**
 * Factory for creating the Jujutsu working copy tool window
 */
class JujutsuToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val jujutsuPanel = JujutsuToolWindowPanel(project)

        val content = ContentFactory.getInstance().createContent(
            jujutsuPanel.getContent(),
            "",
            false
        )
        content.setDisposer(jujutsuPanel)
        toolWindow.contentManager.addContent(content)
    }

    // NOTE: Ideally this would use project.isJujutsu but that doesn't work at startup when tool window availability is
    // evaluated
    override fun shouldBeAvailable(project: Project) = JujutsuRootChecker.findJujutsuRoot(project.basePath) != null
}
