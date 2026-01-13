package `in`.kkkev.jjidea.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import `in`.kkkev.jjidea.ui.log.JujutsuLogPanel
import `in`.kkkev.jjidea.vcs.jujutsuRoots

/**
 * Service that manages opening custom Jujutsu log tabs.
 *
 * Creates custom log tabs with full control over:
 * - Column visibility and ordering
 * - Custom rendering (refs, icons, alignment)
 * - Custom filters and actions
 *
 * Built from scratch using JTable - no dependency on VcsLogUi.
 */
@Service(Service.Level.PROJECT)
class JujutsuCustomLogTabManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(javaClass)
    private val openTabs = mutableListOf<Content>()
    private val openPanels = mutableListOf<JujutsuLogPanel>()
    private var tabCounter = 1

    /**
     * Opens a new custom Jujutsu log tab.
     *
     * Creates a tab with our custom table-based log UI.
     */
    fun openCustomLogTab() {
        log.info("Opening custom Jujutsu log tab")

        try {
            // TODO For now, only handles a single root- extend this to allow for multiple
            val root = project.jujutsuRoots.first().path

            // Create our custom log panel
            val logPanel = JujutsuLogPanel.create(project, root)

            // Register for disposal
            Disposer.register(this, logPanel)

            // Create content tab
            val changesViewContentManager = ChangesViewContentManager.getInstance(project)
            val contentFactory = ContentFactory.getInstance()
            val content = contentFactory.createContent(
                logPanel,
                "Jujutsu Log",
                false
            ).apply {
                isCloseable = true
                preferredFocusableComponent = logPanel
            }

            // Add to changes view (Git tool window area)
            changesViewContentManager.addContent(content)
            changesViewContentManager.setSelectedContent(content)

            openTabs.add(content)
            openPanels.add(logPanel)

            log.info("Custom Jujutsu log tab opened successfully")

        } catch (e: Exception) {
            log.error("Failed to open custom Jujutsu log tab", e)
        }
    }

    /**
     * Refresh all open log tabs.
     * Called when VCS state changes (e.g., after creating a new change).
     *
     * @param selectWorkingCopy If true, select the working copy (@) after refresh
     */
    fun refreshAllTabs(selectWorkingCopy: Boolean = false) {
        log.info("Refreshing ${openPanels.size} open log tabs (selectWorkingCopy=$selectWorkingCopy)")
        openPanels.forEach { panel ->
            panel.refresh(selectWorkingCopy)
        }
    }

    override fun dispose() {
        log.info("Disposing JujutsuCustomLogTabManager")
        openTabs.clear()
        openPanels.clear()
        // Cleanup happens automatically via Disposer
    }

    companion object {
        fun getInstance(project: Project): JujutsuCustomLogTabManager = project.service()
    }
}
