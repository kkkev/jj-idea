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
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.jujutsuRepositories

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

    // Tabs keyed by VCS root path
    private val openTabs = mutableMapOf<JujutsuRepository, Content>()
    private val openPanels = mutableListOf<JujutsuLogPanel>()

    /**
     * Opens a new custom Jujutsu log tab.
     *
     * Creates a tab with our custom table-based log UI.
     */
    fun openCustomLogTab() {
        log.info("Opening custom Jujutsu log tab")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // TODO Each root should have a short name: the directory name if unique, otherwise enough of the parent path up to the project root to guarantee uniqueness
                val roots = project.jujutsuRepositories

                // "content" is the tab to add to the tool window
                // the "panel" is the stuff that goes within it

                val contentFactory = ContentFactory.getInstance()
                val changesViewContentManager = ChangesViewContentManager.getInstance(project)

                ApplicationManager.getApplication().invokeLater {
                    roots.forEach { root ->
                        if (!openTabs.contains(root)) {
                            // Create our custom log panel
                            val logPanel = JujutsuLogPanel(root)

                            // Register for disposal
                            Disposer.register(this, logPanel)

                            // Create content tab
                            // TODO Localise
                            val displayName =
                                "Jujutsu Log" + root.relativePath.let { if (it.isEmpty()) "" else ": $it" }
                            val content = contentFactory.createContent(logPanel, displayName, false)
                                .apply {
                                    isCloseable = false
                                    preferredFocusableComponent = logPanel
                                }

                            // Add to changes view (Git tool window area)
                            changesViewContentManager.addContent(content)
                            changesViewContentManager.setSelectedContent(content)

                            openTabs.put(root, content)
                            openPanels.add(logPanel)

                        }
                    }
                }
                openTabs.filterNot { (root, _) -> root in roots }
                    .forEach { (root, content) ->
                        changesViewContentManager.removeContent(content)
                        openTabs.remove(root)
                    }

                log.info("Custom Jujutsu log tab opened successfully")
            } catch (e: Exception) {
                log.error("Failed to open custom Jujutsu log tab", e)
            }
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
