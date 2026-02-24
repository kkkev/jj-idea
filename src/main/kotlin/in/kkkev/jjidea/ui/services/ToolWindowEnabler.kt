package `in`.kkkev.jjidea.ui.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsListener
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.log.JujutsuCustomLogTabManager
import `in`.kkkev.jjidea.ui.workingcopy.WorkingCopyToolWindowFactory

/**
 * Service that enables/disables Jujutsu tool windows based on VCS roots.
 *
 * Subscribes to VCS configuration changes and shows/hides the JJ log tab and
 * working copy window based on whether any JJ roots are configured.
 *
 * Log suppression rules:
 * - All roots are JJ: Show JJ log, suppress native log
 * - Mixed roots (JJ + other): Show JJ log AND native log
 * - No JJ roots: Hide JJ log, show native log
 */
@Service(Service.Level.PROJECT)
class ToolWindowEnabler(private val project: Project) : Disposable {
    private val log = Logger.getInstance(javaClass)
    private var suppressorListener: ContentManagerListener? = null

    init {
        // Subscribe to VCS configuration changes
        project.messageBus.connect(this).subscribe(
            ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED,
            VcsListener {
                log.debug("VCS configuration changed")
                project.stateModel.initializedRoots.invalidate()
            }
        )

        // React to initializedRoots changes (init() already calls invalidate(),
        // and connect() fires immediately if the value has loaded)
        project.stateModel.initializedRoots.connect(this) { jjRoots ->
            handleRootsChange(jjRoots)
        }
    }

    private fun handleRootsChange(jjRoots: Set<JujutsuRepository>) {
        ApplicationManager.getApplication().invokeLater {
            val hasJjRoots = jjRoots.isNotEmpty()
            val allVcsRoots = ProjectLevelVcsManager.getInstance(project).allVcsRoots
            val totalVcsRoots = allVcsRoots.size
            val allRootsAreJj = hasJjRoots && jjRoots.size == totalVcsRoots

            log.debug(
                "Roots changed: jjRoots=${jjRoots.size}, totalVcsRoots=$totalVcsRoots, allRootsAreJj=$allRootsAreJj"
            )

            // Enable working copy tool window if we have any JJ repos
            ToolWindowManager.getInstance(project)
                .getToolWindow(WorkingCopyToolWindowFactory.TOOL_WINDOW_ID)
                ?.isAvailable = hasJjRoots

            when {
                hasJjRoots && allRootsAreJj -> {
                    // All roots are JJ: show JJ log, suppress native log
                    log.info("All VCS roots are Jujutsu, suppressing native log")
                    closeDefaultVcsLogTabs(project)
                    installDefaultLogTabSuppressor(project)
                    JujutsuCustomLogTabManager.getInstance(project).openCustomLogTab()
                }

                hasJjRoots && !allRootsAreJj -> {
                    // Mixed roots: show both JJ log AND native log
                    log.info("Mixed VCS roots (JJ + other), showing both logs")
                    removeDefaultLogTabSuppressor(project)
                    JujutsuCustomLogTabManager.getInstance(project).openCustomLogTab()
                }

                totalVcsRoots > 0 -> {
                    // No JJ roots but other VCS roots exist: close JJ UI, restore native log
                    log.info("No Jujutsu roots, other VCS roots exist, restoring native log")
                    removeDefaultLogTabSuppressor(project)
                    JujutsuCustomLogTabManager.getInstance(project).closeCustomLogTab()
                }

                else -> {
                    // No VCS roots at all: do nothing, let VCS system clean up naturally
                    // Trying to close tabs or remove listeners can cause VcsLogManager disposed errors
                    log.info("No VCS roots remaining, letting VCS system clean up")
                }
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
        if (suppressorListener != null) return // Already installed

        getVcsToolWindow(project)?.let { vcsToolWindow ->
            val listener = object : ContentManagerListener {
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
            vcsToolWindow.contentManager.addContentManagerListener(listener)
            suppressorListener = listener
            log.debug("Installed default log tab suppressor")
        }
    }

    /**
     * Removes the default log tab suppressor, allowing native VCS logs to be created again.
     */
    private fun removeDefaultLogTabSuppressor(project: Project) {
        suppressorListener?.let { listener ->
            getVcsToolWindow(project)?.contentManager?.removeContentManagerListener(listener)
            suppressorListener = null
            log.debug("Removed default log tab suppressor")
        }
    }

    /**
     * Checks if a tab is a default VCS log tab that should be suppressed.
     */
    private fun isDefaultLogTab(displayName: String?) = displayName == "Log" && !displayName.contains("Jujutsu")

    override fun dispose() {
        removeDefaultLogTabSuppressor(project)
    }

    companion object {
        fun getInstance(project: Project): ToolWindowEnabler = project.service()
    }
}
