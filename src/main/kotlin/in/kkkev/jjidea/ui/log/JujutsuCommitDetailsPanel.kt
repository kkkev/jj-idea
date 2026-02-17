package `in`.kkkev.jjidea.ui.log

import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuFullCommitDetails
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.message
import `in`.kkkev.jjidea.ui.common.JujutsuChangesTree
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.components.*
import `in`.kkkev.jjidea.vcs.actions.JujutsuDataKeys
import java.awt.BorderLayout
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Panel that displays detailed information about a selected commit.
 *
 * Layout matches Git plugin:
 * - TOP: Changed files tree
 * - BOTTOM: Commit metadata and description
 *
 * Note: This panel works with entries from any repository. The repository context
 * is obtained from the `LogEntry.repo` field when needed.
 */
class JujutsuCommitDetailsPanel(project: Project) : JPanel(BorderLayout()), Disposable {
    private val log = Logger.getInstance(javaClass)

    private val metadataPanel = JPanel(BorderLayout())
    private val changesPanel = JPanel(BorderLayout())
    private val splitter: OnePixelSplitter

    // Metadata components
    private val metadataPane = IconAwareHtmlPane()

    // Changes tree
    private val changesTree = JujutsuChangesTree(project)

    // Current selected entry
    private var currentEntry: LogEntry? = null

    init {
        // Configure metadata pane
        metadataPane.apply {
            background = UIUtil.getTextFieldBackground()
            border = JBUI.Borders.empty(8)
        }

        metadataPanel.add(JBScrollPane(metadataPane), BorderLayout.CENTER)

        // Setup changes panel with tree and toolbar
        setupChangesPanel()

        // Inject LOG_ENTRY into data context for actions to determine working copy vs historical
        changesTree.additionalDataProvider = { sink ->
            currentEntry?.let { sink[JujutsuDataKeys.LOG_ENTRY] = it }
        }

        // Create splitter: changes on top, metadata on bottom
        splitter = OnePixelSplitter(true, 0.5f).apply {
            firstComponent = changesPanel
            secondComponent = metadataPanel
        }

        add(splitter, BorderLayout.CENTER)

        // Setup tree interactions
        setupTreeInteractions()

        // Show empty state initially
        showEmptyState()
    }

    private fun setupChangesPanel() {
        // Add toolbar
        val toolbar = createChangesToolbar()
        changesPanel.add(toolbar.component, BorderLayout.NORTH)

        // Add tree
        val treeScrollPane = ScrollPaneFactory.createScrollPane(changesTree)
        changesPanel.add(treeScrollPane, BorderLayout.CENTER)
    }

    private fun createChangesToolbar(): ActionToolbar {
        val group = DefaultActionGroup()

        // Tree expander actions (expand all / collapse all)
        val treeExpander = changesTree.treeExpander
        val commonActionsManager = CommonActionsManager.getInstance()
        group.add(commonActionsManager.createExpandAllAction(treeExpander, changesTree))
        group.add(commonActionsManager.createCollapseAllAction(treeExpander, changesTree))

        group.addSeparator()

        // Grouping actions
        group.add(ActionManager.getInstance().getAction("ChangesView.GroupBy"))

        return ActionManager.getInstance()
            .createActionToolbar("JujutsuCommitDetailsChangesToolbar", group, true)
            .apply {
                targetComponent = changesTree
            }
    }

    private fun setupTreeInteractions() {
        changesTree.installHandlers()
    }

    /**
     * Update the panel to show details for the given commit.
     */
    fun showCommit(entry: LogEntry?) {
        currentEntry = entry

        if (entry == null) {
            showEmptyState()
            changesTree.setChangesToDisplay(emptyList())
            return
        }

        // Update metadata immediately
        val html = buildCommitHtml(entry)
        metadataPane.text = html

        // Scroll to top
        SwingUtilities.invokeLater {
            (metadataPane as JEditorPane).caretPosition = 0
        }

        // Load changes in background
        loadChanges(entry)
    }

    private fun loadChanges(entry: LogEntry) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val fullDetails = JujutsuFullCommitDetails.create(entry, entry.repo.directory)
                val changes = fullDetails.changes.toList()

                ApplicationManager.getApplication().invokeLater {
                    if (currentEntry == entry) { // Only update if still the same commit
                        changesTree.setChangesToDisplay(changes)
                        // Expand all nodes by default after loading changes
                        changesTree.invokeAfterRefresh {
                            changesTree.treeExpander.expandAll()
                        }
                    }
                }
            } catch (e: Exception) {
                // This can happen when a commit is removed (e.g., by abandon, or empty commit auto-removed).
                // Treat this as "no commit selected" rather than an error.
                log.info("Change ${entry.id} no longer exists (likely abandoned or auto-removed): ${e.message}")
                ApplicationManager.getApplication().invokeLater {
                    if (currentEntry == entry) {
                        // Clear the selection state since this commit no longer exists
                        currentEntry = null
                        showEmptyState()
                        changesTree.setChangesToDisplay(emptyList())
                    }
                }
            }
        }
    }

    /**
     * Build HTML for commit details, matching Git plugin style.
     */
    private fun buildCommitHtml(entry: LogEntry) = htmlString {
        control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
            appendSummaryAndStatuses(entry)
            appendParents(entry)
            control("<pre style='white-space: pre-wrap;'>", "</pre>") {
                append(entry.description)
            }
            control("<p style='margin: 4px 0;'>", "</p>") {
                entry.author?.let { append(it) } ?: append("Unknown")
                entry.authorTimestamp?.also {
                    append(" on ")
                    append(it)
                }

                // Committer line (if different from author)
                val committer = entry.committer
                if (committer != null && committer != entry.author) {
                    append("\ncommitted by ")
                    append(committer)
                }
            }
        }
    }

    /**
     * Show empty state when no commit is selected.
     */
    private fun showEmptyState() {
        metadataPane.text = htmlString {
            control("<body style='${Formatters.getBodyStyle()}; padding: 8px'>", "</body>") {
                grey { italic { append(message("details.empty.message")) } }
            }
        }
    }

    override fun dispose() {
        // Cleanup if needed
    }
}
