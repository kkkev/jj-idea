package `in`.kkkev.jjidea.ui.log

import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.message
import `in`.kkkev.jjidea.ui.common.JujutsuChangesTree
import `in`.kkkev.jjidea.ui.components.*
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import javax.swing.JPanel

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
    private val metadataPane = IconAwareHtmlPane(project)

    // Changes tree
    private val changesTree = JujutsuChangesTree(project)

    // Current selected entries
    private var currentEntries: List<LogEntry> = emptyList()

    init {
        // Configure metadata pane
        metadataPane.apply {
            background = UIUtil.getTextFieldBackground()
            border = JBUI.Borders.empty(8)
        }

        metadataPanel.add(JBScrollPane(metadataPane), BorderLayout.CENTER)

        // Setup changes panel with tree and toolbar
        setupChangesPanel()

        // Inject LOG_ENTRY into data context for actions to determine working copy vs historical.
        // Prefer the working copy entry if one is in the selection, otherwise use the first entry.
        changesTree.additionalDataProvider = { sink ->
            val entry = currentEntries.firstOrNull { it.isWorkingCopy } ?: currentEntries.firstOrNull()
            entry?.let { sink[JujutsuDataKeys.LOG_ENTRY] = it }
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
    fun showCommit(entry: LogEntry?) = showCommits(listOfNotNull(entry))

    /**
     * Update the panel to show details for the given commits.
     * For multiple commits, metadata is displayed as stacked sections with <hr> separators,
     * and the changes tree shows the union of all selected commits' changes.
     */
    fun showCommits(entries: List<LogEntry>) {
        currentEntries = entries

        if (entries.isEmpty()) {
            showEmptyState()
            changesTree.setChangesToDisplay(emptyList())
            return
        }

        // Update metadata immediately
        metadataPane.text = buildCommitHtml(entries)

        // Scroll to top after text is set (runLater so layout completes first)
        runLater { metadataPane.caretPosition = 0 }

        // Load changes in background
        loadChanges(entries)
    }

    private fun loadChanges(entries: List<LogEntry>) {
        runInBackground {
            try {
                val changes = ChangeService.loadChanges(entries)
                runLater {
                    if (currentEntries == entries) {
                        changesTree.setChangesToDisplay(changes)
                        changesTree.invokeAfterRefresh { changesTree.treeExpander.expandAll() }
                    }
                }
            } catch (e: Exception) {
                // This can happen when a commit is removed (e.g., by abandon, or empty commit auto-removed).
                // Treat this as "no commit selected" rather than an error.
                val ids = entries.joinToString { it.id.toString() }
                log.info("Change(s) $ids no longer exist (likely abandoned or auto-removed): ${e.message}")
                runLater {
                    if (currentEntries == entries) {
                        currentEntries = emptyList()
                        showEmptyState()
                        changesTree.setChangesToDisplay(emptyList())
                    }
                }
            }
        }
    }

    /**
     * Build HTML for one or more commit details.
     * Multiple entries are separated by <hr> dividers, capped at MAX_DISPLAYED_COMMITS.
     */
    private fun buildCommitHtml(entries: List<LogEntry>) = htmlString {
        val displayed = entries.take(MAX_DISPLAYED_COMMITS)
        control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
            displayed.forEachIndexed { index, entry ->
                if (index > 0) control("<hr/>")
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
                    val committer = entry.committer
                    if (committer != null && committer != entry.author) {
                        append("\ncommitted by ")
                        append(committer)
                    }
                }
            }
            if (entries.size > MAX_DISPLAYED_COMMITS) {
                control("<p>", "</p>") {
                    grey {
                        append(
                            JujutsuBundle.message("details.multi.overflow", MAX_DISPLAYED_COMMITS, entries.size)
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_DISPLAYED_COMMITS = 20
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
