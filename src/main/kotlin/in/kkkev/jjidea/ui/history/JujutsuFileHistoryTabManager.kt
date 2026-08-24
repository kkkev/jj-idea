package `in`.kkkev.jjidea.ui.history

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.util.runLater

/**
 * Service that manages file history tabs in the VCS tool window.
 *
 * Each file gets its own tab showing commit history with the same
 * formatting as the custom log view (but without the commit graph).
 */
@Service(Service.Level.PROJECT)
class JujutsuFileHistoryTabManager(private val project: Project) : Disposable {
    private val log = Logger.getInstance(javaClass)

    // Map of open file history tabs by file path
    private val openTabs = mutableMapOf<String, Content>()

    /**
     * Open a file history tab for the given file.
     *
     * If a tab for this file is already open, selects it.
     * Otherwise, creates a new tab.
     */
    fun openFileHistory(filePath: FilePath, repo: JujutsuRepository) {
        log.info("Opening file history for: ${filePath.path}")

        runLater {
            val pathKey = filePath.path

            // Check if tab already exists
            val existingContent = openTabs[pathKey]
            if (existingContent != null) {
                // Tab exists, just select it
                val changesViewContentManager = ChangesViewContentManager.getInstance(project)
                changesViewContentManager.setSelectedContent(existingContent)
                ChangesViewContentManager.getToolWindowFor(project, existingContent.displayName)?.activate(null)
                log.info("Selected existing file history tab for: ${filePath.name}")
                return@runLater
            }

            // Create new file history panel
            val historyPanel = JujutsuFileHistoryPanel(project, filePath, repo)

            // Register for disposal
            Disposer.register(this, historyPanel)

            // Create content tab
            val tabTitle = JujutsuBundle.message("history.tab.title", filePath.name)
            val content = ContentFactory.getInstance().createContent(historyPanel, tabTitle, false).apply {
                isCloseable = true
                preferredFocusableComponent = historyPanel

                // Remove from tracking when tab is closed
                setDisposer {
                    openTabs.remove(pathKey)
                    log.info("File history tab closed for: ${filePath.name}")
                }
            }

            // Add to changes view
            val changesViewContentManager = ChangesViewContentManager.getInstance(project)
            changesViewContentManager.addContent(content)
            changesViewContentManager.setSelectedContent(content)
            ChangesViewContentManager.getToolWindowFor(project, content.displayName)?.activate(null)

            // Track the tab
            openTabs[pathKey] = content

            log.info("File history tab opened for: ${filePath.name}")
        }
    }

    /**
     * Detaches every open tab's [Content] from the platform-owned [ChangesViewContentManager]
     * before this service (and its child [JujutsuFileHistoryPanel]s) are disposed — otherwise the
     * platform's content list, which outlives the plugin across a dynamic unload/reload
     * (jj-idea-nd8x), keeps showing the stale panel.
     *
     * Runs synchronously, not via [runLater]: dispose for a plugin unload runs on the EDT under a
     * write action, and this must complete before the classloader becomes unreachable.
     *
     * Removes with `dispose = false` and clears [openTabs] up front, rather than relying on each
     * [Content]'s close-disposer (registered in [openFileHistory]) to do it — that disposer mutates
     * [openTabs] itself, which would throw a `ConcurrentModificationException` if fired while this
     * loop is iterating the same map.
     */
    override fun dispose() {
        log.info("Disposing JujutsuFileHistoryTabManager")
        val contents = openTabs.values.toList()
        openTabs.clear()
        for (content in contents) {
            try {
                content.manager?.removeContent(content, false)
            } catch (e: Exception) {
                log.warn("Failed to remove file history tab from content manager", e)
            }
        }
    }

    companion object {
        fun getInstance(project: Project): JujutsuFileHistoryTabManager = project.service()
    }
}
