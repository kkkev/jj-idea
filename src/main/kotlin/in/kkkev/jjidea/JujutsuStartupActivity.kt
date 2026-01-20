package `in`.kkkev.jjidea

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.JujutsuCustomLogTabManager
import `in`.kkkev.jjidea.vcs.isJujutsu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Startup activity that automatically opens the custom Jujutsu log tab
 * when a Jujutsu project is detected.
 *
 * This replaces the standard VCS log with our custom implementation by:
 * 1. Closing any default VCS log tabs
 * 2. Opening the custom Jujutsu log tab
 */
class JujutsuStartupActivity : ProjectActivity {
    private val log = Logger.getInstance(javaClass)

    override suspend fun execute(project: Project) {
        log.debug("JujutsuStartupActivity executing for project: ${project.name}")

        // Check if auto-open is enabled
        val settings = JujutsuSettings.getInstance(project)
        if (!settings.state.autoOpenCustomLogTab) {
            log.debug("Auto-open custom log tab is disabled in settings")
            return
        }

        if (project.isJujutsu) {
            log.info("Jujutsu project detected, replacing standard VCS log with custom implementation")

            // Open the custom log tab on EDT (UI modifications must happen on EDT)
            withContext(Dispatchers.EDT) {
                // Close any default VCS log tabs first
                closeDefaultVcsLogTabs(project)

                // Install listener to prevent default log tabs from being recreated
                installDefaultLogTabSuppressor(project)

                // Open our custom log tab
                JujutsuCustomLogTabManager.getInstance(project).openCustomLogTab()
            }
        } else {
            log.debug("Not a Jujutsu project, skipping custom log tab")
        }
    }

    /**
     * Installs a listener that automatically closes default VCS log tabs when they're created.
     * This prevents the standard log from reappearing after we close it.
     */
    private fun installDefaultLogTabSuppressor(project: Project) {
        getVcsToolWindow(project)?.let { vcsToolWindow ->
            vcsToolWindow.contentManager.addContentManagerListener(
                object : ContentManagerListener {
                    override fun contentAdded(event: ContentManagerEvent) {
                        if (isDefaultLogTab(event.content.displayName)) {
                            log.info("Suppressing default VCS log tab: ${event.content.displayName}")
                            vcsToolWindow.contentManager.removeContent(event.content, true)
                        }
                    }
                }
            )
            log.debug("Installed default log tab suppressor")
        }
    }

    /**
     * Closes default VCS log tabs to replace them with our custom implementation.
     */
    private fun closeDefaultVcsLogTabs(project: Project) {
        getVcsToolWindow(project)?.let { vcsToolWindow ->
            val tabsToClose = vcsToolWindow.contentManager.contents.filter { isDefaultLogTab(it.displayName) }

            tabsToClose.forEach { content ->
                log.debug("Closing default VCS log tab: ${content.displayName}")
                vcsToolWindow.contentManager.removeContent(content, true)
            }

            if (tabsToClose.isNotEmpty()) {
                log.info("Closed ${tabsToClose.size} default VCS log tab(s)")
            }
        }
    }

    /**
     * Gets the VCS tool window for the project.
     */
    private fun getVcsToolWindow(project: Project) =
        try {
            ToolWindowManager
                .getInstance(project)
                .getToolWindow("Version Control")
                ?.also { log.debug("Found VCS tool window") }
                ?: run {
                    log.debug("No VCS tool window found")
                    null
                }
        } catch (e: Exception) {
            log.warn("Failed to get VCS tool window", e)
            null
        }

    /**
     * Checks if a tab is a default VCS log tab that should be suppressed.
     */
    private fun isDefaultLogTab(displayName: String?) = displayName == "Log" && !displayName.contains("Jujutsu")
}
