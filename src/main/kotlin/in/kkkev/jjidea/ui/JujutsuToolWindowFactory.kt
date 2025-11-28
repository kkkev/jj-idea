package `in`.kkkev.jjidea.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory for creating the Jujutsu tool window
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

    override fun shouldBeAvailable(project: Project): Boolean {
        // Only show if Jujutsu VCS is configured for this project
        // For now, always show it
        return true
    }
}
