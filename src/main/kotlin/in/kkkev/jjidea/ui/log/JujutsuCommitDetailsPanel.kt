package `in`.kkkev.jjidea.ui.log

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.ChangeService
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.message
import `in`.kkkev.jjidea.ui.common.JujutsuChangesTree
import `in`.kkkev.jjidea.ui.common.JujutsuEditorTabDiffPreview
import `in`.kkkev.jjidea.ui.common.changesTreeToolbar
import `in`.kkkev.jjidea.ui.components.*
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import kotlin.time.Instant

/**
 * Renders [user] as `Name <email>` (or "Unknown" if absent), followed by a non-breaking gap and
 * an unbreakable "middle-dot <timestamp>" chip if [timestamp] is present. Shared by the author
 * and committer lines so their spacing/wrap behavior can't drift apart the way it once did
 * (jj-idea-vll4, jj-idea-m2wr).
 */
private fun TextCanvas.appendUserWithTimestamp(user: VcsUser?, timestamp: Instant?) {
    user?.let { appendWithEmail(it) } ?: append("Unknown")
    timestamp?.also {
        // append (not appendUnbreakable): stays a breakable wrap point on a long line, and its
        // escaping turns the space into a non-collapsing &nbsp; matching the date chip's own
        // leading \u00a0 below, so both gaps measure to the same width.
        append(" ")
        appendUnbreakable("\u00b7\u00a0${DateTimeFormatter.formatAbsolute(it)}")
    }
}

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
class JujutsuCommitDetailsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, UiDataProvider {
    private val log = Logger.getInstance(javaClass)

    private val metadataPanel = JPanel(BorderLayout())
    private val changesPanel = JPanel(BorderLayout())
    private val splitter: OnePixelSplitter

    // Metadata components
    private val metadataPane = IconAwareHtmlPane(project)

    // Changes tree
    private val changesTree = JujutsuChangesTree(project)
    internal val diffPreview = JujutsuEditorTabDiffPreview(changesTree) {
        when (currentEntries.size) {
            0 -> null
            1 -> currentEntries.first().id.short
            else -> "${currentEntries.size} changes"
        }
    }

    // Current selected entries
    private var currentEntries: List<LogEntry> = emptyList()

    init {
        Disposer.register(this, diffPreview)

        // Configure metadata pane
        metadataPane.apply {
            background = UIUtil.getTextFieldBackground()
            border = JBUI.Borders.empty(8)
        }

        // Right-click on a ref chip (bookmark/tag) or an author/committer email in the metadata
        // pane → per-element context menu (jj-idea-a52h: email right-click was missing entirely -
        // only jjref:// hrefs were ever resolved here).
        metadataPane.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = handlePopupTrigger(e)
            override fun mouseReleased(e: MouseEvent) = handlePopupTrigger(e)

            private fun handlePopupTrigger(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                // A linkified issue-tracker substring inside a bookmark/tag chip's own label
                // (jj-idea-vrmv follow-up) takes priority over the chip's own jjref:// href - it's the
                // more specific target under the cursor.
                val issueLinkUri = metadataPane.issueLinkUriAt(e.point)
                val target = if (issueLinkUri != null) {
                    IssueLinkClick(issueLinkUri)
                } else {
                    val href = metadataPane.hrefAt(e.point) ?: return
                    val uri = runCatching { java.net.URI(href) }.getOrNull() ?: return
                    LogClickTarget.resolve(uri, project, currentEntries) ?: return
                }
                val actionGroup = JujutsuLogContextMenuActions.clickActionGroup(project, target)
                ActionManager.getInstance()
                    .createActionPopupMenu("JujutsuRefPopup", actionGroup)
                    .component.show(metadataPane, e.x, e.y)
                e.consume()
            }
        })

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
        val toolbar = changesTreeToolbar(changesTree, "JujutsuCommitDetailsChangesToolbar")
        changesPanel.add(toolbar.component, BorderLayout.NORTH)

        // Add tree
        val treeScrollPane = ScrollPaneFactory.createScrollPane(changesTree)
        changesPanel.add(treeScrollPane, BorderLayout.CENTER)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[DiffDataKeys.EDITOR_TAB_DIFF_PREVIEW] = diffPreview
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
        // Selection-preservation (jj-idea-yje9) can re-fire this with an unchanged selection;
        // skip the HTML rebuild and background change-load when nothing actually changed.
        if (entries == currentEntries) return
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
    private fun buildCommitHtml(entries: List<LogEntry>) = htmlString(
        linkifier = IssueLinkifier(IssueNavigationConfiguration.getInstance(project))
    ) {
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
                    appendUserWithTimestamp(entry.author, entry.authorTimestamp)
                    val committer = entry.committer
                    if (committer != null && committer != entry.author) {
                        append("\ncommitted by ")
                        appendUserWithTimestamp(committer, entry.committerTimestamp)
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
