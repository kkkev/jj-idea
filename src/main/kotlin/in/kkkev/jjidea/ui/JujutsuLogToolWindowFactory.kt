package `in`.kkkev.jjidea.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory for creating the Jujutsu Log tool window (bottom pane)
 */
class JujutsuLogToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val logPanel = JujutsuLogPanel(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(logPanel.getContent(), "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
