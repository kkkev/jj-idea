package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import `in`.kkkev.jjidea.jj.JjAvailabilityChecker
import `in`.kkkev.jjidea.jj.JjAvailabilityStatus
import `in`.kkkev.jjidea.ui.common.JjNotInstalledPanel
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.jujutsuRepositories
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JPanel

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

    // Wrapper panel with CardLayout for switching between log and not-installed states
    private var wrapperPanel: JPanel? = null
    private var cardLayout: CardLayout? = null
    private var notInstalledPanel: JPanel? = null

    companion object {
        private const val CARD_LOG = "log"
        private const val CARD_NOT_INSTALLED = "notInstalled"

        fun getInstance(project: Project): JujutsuCustomLogTabManager = project.service()
    }

    /**
     * Opens the unified Jujutsu log tab.
     *
     * Creates a single tab showing commits from all JJ repositories
     * with a root filter for multi-root projects.
     */
    fun openCustomLogTab() {
        log.info("Opening unified Jujutsu log tab")

        runInBackground {
            try {
                val roots = project.jujutsuRepositories

                if (roots.isEmpty()) {
                    log.info("No Jujutsu repositories found, skipping log tab creation")
                    return@runInBackground
                }

                val contentFactory = ContentFactory.getInstance()
                val changesViewContentManager = ChangesViewContentManager.getInstance(project)

                runLater {
                    // Only create one unified tab if not already open
                    if (unifiedLogContent == null) {
                        // Create wrapper panel with CardLayout
                        val layout = CardLayout()
                        cardLayout = layout
                        val wrapper = JPanel(layout)
                        wrapperPanel = wrapper

                        // Create unified log panel for all roots
                        val logPanel = UnifiedJujutsuLogPanel(project)
                        unifiedLogPanel = logPanel

                        // Register for disposal
                        Disposer.register(this, logPanel)

                        // Add log panel to wrapper
                        wrapper.add(logPanel, CARD_LOG)

                        // Create placeholder not-installed panel (will be updated by status listener)
                        val notInstalled = JPanel(BorderLayout())
                        notInstalledPanel = notInstalled
                        wrapper.add(notInstalled, CARD_NOT_INSTALLED)

                        // Subscribe to availability status changes
                        subscribeToAvailabilityStatus()

                        // Create content tab
                        val content = contentFactory.createContent(wrapper, "Jujutsu Log", false)
                            .apply {
                                isCloseable = false
                                preferredFocusableComponent = wrapper
                            }

                        // Add to changes view (Git tool window area)
                        changesViewContentManager.addContent(content)
                        changesViewContentManager.setSelectedContent(content)

                        unifiedLogContent = content
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

    private fun subscribeToAvailabilityStatus() {
        val checker = JjAvailabilityChecker.getInstance(project)
        checker.status.connect(this) { status -> updateForAvailabilityStatus(status) }

        // Check current status immediately
        updateForAvailabilityStatus(checker.status.value)
    }

    private fun updateForAvailabilityStatus(status: JjAvailabilityStatus) {
        val wrapper = wrapperPanel ?: return
        val layout = cardLayout ?: return

        when (status) {
            is JjAvailabilityStatus.Available -> {
                layout.show(wrapper, CARD_LOG)
            }

            else -> {
                // Replace the not-installed panel with fresh content
                notInstalledPanel?.let { wrapper.remove(it) }
                val newNotInstalledPanel = JjNotInstalledPanel(project, status)
                notInstalledPanel = newNotInstalledPanel
                wrapper.add(newNotInstalledPanel, CARD_NOT_INSTALLED)
                layout.show(wrapper, CARD_NOT_INSTALLED)
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

        runLater {
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
        wrapperPanel = null
        cardLayout = null
        notInstalledPanel = null
        // Cleanup happens automatically via Disposer
    }
}
