package `in`.kkkev.jjidea.ui

import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.log.JujutsuCustomLogTabManager
import `in`.kkkev.jjidea.ui.workingcopy.WorkingCopyToolWindowFactory

/**
 * VCS listener to check for Jujutsu roots to determine whether to show/hide the various tool windows and tabs
 * associated with this plugin.
 */
class ToolWindowEnabler(val project: Project) : VcsRepositoryMappingListener {
    private val log = Logger.getInstance(javaClass)
    private var listenerInstalled = false

    override fun mappingChanged() {
        // Invalidate the cache since VCS mappings have changed
        // The initializedRoots loader will query the VCS manager on a background thread
        project.stateModel.initializedRoots.invalidate()

        // Install listener once to react to cache updates
        if (!listenerInstalled) {
            listenerInstalled = true
            project.stateModel.initializedRoots.connect(project) { _, new ->
                handleRootsChange(new.isNotEmpty())
            }
        }
    }

    private fun handleRootsChange(showToolWindows: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            // Firstly, enable tool window if we have a JJ repo
            ToolWindowManager.getInstance(project)
                .getToolWindow(WorkingCopyToolWindowFactory.TOOL_WINDOW_ID)
                ?.isAvailable = showToolWindows

            if (showToolWindows) {
                log.info("Jujutsu project detected, replacing standard VCS log with custom implementation")

                // Close any default VCS log tabs first
                closeDefaultVcsLogTabs(project)

                // Install listener to prevent default log tabs from being recreated
                installDefaultLogTabSuppressor(project)

                // Open our custom log tab
                JujutsuCustomLogTabManager.getInstance(project).openCustomLogTab()
            } else {
                log.debug("Not a Jujutsu project, skipping custom log tab")
            }
        }
    }

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

    private fun getVcsToolWindow(project: Project) =
        try {
            ToolWindowManager.getInstance(project)
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
                            // Defer removal to allow the platform to complete initialization
                            // Removing synchronously causes disposal errors (IncorrectOperationException)
                            ApplicationManager.getApplication().invokeLater {
                                vcsToolWindow.contentManager.removeContent(event.content, true)
                            }
                        }
                    }
                }
            )
            log.debug("Installed default log tab suppressor")
        }
    }

    /**
     * Checks if a tab is a default VCS log tab that should be suppressed.
     */
    private fun isDefaultLogTab(displayName: String?) = displayName == "Log" && !displayName.contains("Jujutsu")
}
