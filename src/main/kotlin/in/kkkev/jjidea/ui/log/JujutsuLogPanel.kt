package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ScrollPaneFactory
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Main panel for Jujutsu commit log UI.
 *
 * Layout:
 * - NORTH: Toolbar (refresh, filters - Phase 4)
 * - CENTER: Log table with commits
 * - SOUTH: Details pane (Phase 4)
 *
 * Built from scratch - no dependency on IntelliJ's VCS log UI.
 */
class JujutsuLogPanel(
    private val project: Project,
    private val root: VirtualFile
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(JujutsuLogPanel::class.java)

    // Table showing commits
    private val logTable = JujutsuLogTable()

    // Data loader for background loading
    private val dataLoader = JujutsuLogDataLoader(project, root, logTable.logModel, logTable)

    init {
        // Install custom renderers
        logTable.installRenderers()

        // Add table in scroll pane
        val scrollPane = ScrollPaneFactory.createScrollPane(logTable)
        add(scrollPane, BorderLayout.CENTER)

        // Add toolbar
        add(createToolbar(), BorderLayout.NORTH)

        // Load initial data
        dataLoader.loadCommits()

        log.info("JujutsuLogPanel initialized for root: ${root.path}")
    }

    private fun createToolbar(): JPanel {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "JujutsuLogToolbar",
            createActionGroup(),
            true
        )

        toolbar.targetComponent = this

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.CENTER)
        }
    }

    private fun createActionGroup() = DefaultActionGroup().apply {
        add(RefreshAction())
        // Phase 4: Add filter, search, etc.
    }

    /**
     * Refresh action - reload commits.
     */
    private inner class RefreshAction : AnAction(
        "Refresh",
        "Reload commits from Jujutsu",
        AllIcons.Actions.Refresh
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            log.info("Refresh action triggered")
            dataLoader.refresh()
        }
    }

    override fun dispose() {
        log.info("JujutsuLogPanel disposed")
        // Cleanup will happen automatically
    }

    companion object {
        /**
         * Create a new log panel for the given project and root.
         */
        fun create(project: Project, root: VirtualFile) =
            JujutsuLogPanel(project, root)
    }
}
