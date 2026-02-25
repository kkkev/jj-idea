package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import `in`.kkkev.jjidea.vcs.jujutsuRepositories

/**
 * Service that manages opening custom Jujutsu log tabs.
 *
 * Creates a unified log tab that shows commits from all JJ repositories
 * with filtering capability by root.
 *
 * Built from scratch using JTable - no dependency on VcsLogUi.
 */
@Service(Service.Level.PROJECT)
class JujutsuCustomLogTabManager(private val project: Project) : Disposable {
    private val log = Logger.getInstance(javaClass)

    // Single unified log tab content
    private var unifiedLogContent: Content? = null
    private var unifiedLogPanel: UnifiedJujutsuLogPanel? = null

    /**
     * Opens the unified Jujutsu log tab.
     *
     * Creates a single tab showing commits from all JJ repositories
     * with a root filter for multi-root projects.
     */
    fun openCustomLogTab() {
        log.info("Opening unified Jujutsu log tab")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val roots = project.jujutsuRepositories

                if (roots.isEmpty()) {
                    log.info("No Jujutsu repositories found, skipping log tab creation")
                    return@executeOnPooledThread
                }

                val contentFactory = ContentFactory.getInstance()
                val changesViewContentManager = ChangesViewContentManager.getInstance(project)

                ApplicationManager.getApplication().invokeLater {
                    // Only create one unified tab if not already open
                    if (unifiedLogContent == null) {
                        // Create unified log panel for all roots
                        val logPanel = UnifiedJujutsuLogPanel(project)

                        // Register for disposal
                        Disposer.register(this, logPanel)

                        // Create content tab
                        val content = contentFactory.createContent(logPanel, "Jujutsu Log", false)
                            .apply {
                                isCloseable = false
                                preferredFocusableComponent = logPanel
                            }

                        // Add to changes view (Git tool window area)
                        changesViewContentManager.addContent(content)
                        changesViewContentManager.setSelectedContent(content)

                        unifiedLogContent = content
                        unifiedLogPanel = logPanel
                    } else {
                        // Tab already exists, just select it
                        unifiedLogContent?.let { changesViewContentManager.setSelectedContent(it) }
                    }
                }

                log.info("Unified Jujutsu log tab opened successfully")
            } catch (e: Exception) {
                log.error("Failed to open unified Jujutsu log tab", e)
            }
        }
    }

    /**
     * Activate the Jujutsu log tab: select it and bring the tool window to the front.
     * Used when navigating to a commit from annotations or other entry points.
     */
    fun activateLogTab() {
        val content = unifiedLogContent ?: return
        val changesViewContentManager = ChangesViewContentManager.getInstance(project)
        changesViewContentManager.setSelectedContent(content)
        ChangesViewContentManager.getToolWindowFor(project, content.displayName)?.activate(null)
    }

    /**
     * Closes the unified Jujutsu log tab.
     *
     * Called when JJ roots are removed from the project, allowing the native VCS log to return.
     */
    fun closeCustomLogTab() {
        log.info("Closing unified Jujutsu log tab")

        ApplicationManager.getApplication().invokeLater {
            unifiedLogContent?.let { content ->
                try {
                    ChangesViewContentManager.getInstance(project).removeContent(content)
                    log.info("Unified Jujutsu log tab closed successfully")
                } catch (e: Exception) {
                    log.warn("Failed to close unified Jujutsu log tab", e)
                }
            }
            unifiedLogContent = null
            unifiedLogPanel = null
        }
    }

    override fun dispose() {
        log.info("Disposing JujutsuCustomLogTabManager")
        unifiedLogContent = null
        unifiedLogPanel = null
        // Cleanup happens automatically via Disposer
    }

    companion object {
        fun getInstance(project: Project): JujutsuCustomLogTabManager = project.service()
    }
}
