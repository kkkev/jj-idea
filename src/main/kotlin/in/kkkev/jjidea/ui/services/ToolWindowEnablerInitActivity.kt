package `in`.kkkev.jjidea.ui.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Initializes ToolWindowEnabler on project open.
 * Replaces deprecated `preload="true"` on the service registration.
 */
class ToolWindowEnablerInitActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ToolWindowEnabler.getInstance(project)
    }
}
