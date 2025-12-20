package `in`.kkkev.jjidea.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import `in`.kkkev.jjidea.ui.log.JujutsuLogPanel
import `in`.kkkev.jjidea.vcs.JujutsuVcs

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

    private val log = Logger.getInstance(JujutsuCustomLogTabManager::class.java)
    private val openTabs = mutableListOf<Content>()
    private var tabCounter = 1

    /**
     * Opens a new custom Jujutsu log tab.
     *
     * Creates a tab with our custom table-based log UI.
     */
    fun openCustomLogTab() {
        log.info("Opening custom Jujutsu log tab")

        try {
            // Find Jujutsu VCS root
            val vcsManager = ProjectLevelVcsManager.getInstance(project)
            val jujutsuRoot = vcsManager.allVcsRoots
                .firstOrNull { it.vcs is JujutsuVcs }

            if (jujutsuRoot == null) {
                log.warn("No Jujutsu VCS root found in project")
                return
            }

            val root = jujutsuRoot.path

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

            log.info("Custom Jujutsu log tab opened successfully")

        } catch (e: Exception) {
            log.error("Failed to open custom Jujutsu log tab", e)
        }
    }

    override fun dispose() {
        log.info("Disposing JujutsuCustomLogTabManager")
        // Cleanup happens automatically via Disposer
    }

    companion object {
        fun getInstance(project: Project): JujutsuCustomLogTabManager = project.service()
    }
}
