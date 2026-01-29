package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import `in`.kkkev.jjidea.vcs.JujutsuRootChecker

/**
 * Factory for creating the Jujutsu working copy tool window.
 * Creates a single unified panel that shows all repositories with grouping support.
 */
class WorkingCopyToolWindowFactory : ToolWindowFactory, DumbAware {
    companion object {
        /** Tool window ID - must match the id in plugin.xml */
        const val TOOL_WINDOW_ID = "Working copy"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = UnifiedWorkingCopyPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    // Check for .jj directory directly - this is a quick filesystem check that's safe on EDT
    // and works before VCS configuration is fully loaded.
    // The ToolWindowEnabler and JujutsuStateModel will handle dynamic updates later.
    override fun shouldBeAvailable(project: Project) =
        project.guessProjectDir()?.let { JujutsuRootChecker.isJujutsuRoot(it) } ?: false
}
