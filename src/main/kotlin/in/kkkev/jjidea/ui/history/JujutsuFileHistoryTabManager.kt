package `in`.kkkev.jjidea.ui.history

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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

        ApplicationManager.getApplication().invokeLater {
            val pathKey = filePath.path

            // Check if tab already exists
            val existingContent = openTabs[pathKey]
            if (existingContent != null) {
                // Tab exists, just select it
                val changesViewContentManager = ChangesViewContentManager.getInstance(project)
                changesViewContentManager.setSelectedContent(existingContent)
                log.info("Selected existing file history tab for: ${filePath.name}")
                return@invokeLater
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

            // Track the tab
            openTabs[pathKey] = content

            log.info("File history tab opened for: ${filePath.name}")
        }
    }

    override fun dispose() {
        log.info("Disposing JujutsuFileHistoryTabManager")
        openTabs.clear()
    }

    companion object {
        fun getInstance(project: Project): JujutsuFileHistoryTabManager = project.service()
    }
}
