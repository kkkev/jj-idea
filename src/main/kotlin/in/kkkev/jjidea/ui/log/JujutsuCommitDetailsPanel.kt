package `in`.kkkev.jjidea.ui.log

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuFullCommitDetails
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.*
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit

/**
 * Panel that displays detailed information about a selected commit.
 *
 * Layout matches Git plugin:
 * - TOP: Changed files tree
 * - BOTTOM: Commit metadata and description
 */
class JujutsuCommitDetailsPanel(private val project: Project, private val root: VirtualFile) : JPanel(BorderLayout()),
    Disposable {
    private val log = Logger.getInstance(javaClass)

    private val metadataPanel = JPanel(BorderLayout())
    private val changesPanel = JPanel(BorderLayout())
    private val splitter: OnePixelSplitter

    // Metadata components
    private val metadataPane = JEditorPane()

    // Changes tree
    private val changesTree = JujutsuChangesTree(project)

    // Current selected entry
    private var currentEntry: LogEntry? = null

    init {
        // Configure metadata pane
        metadataPane.apply {
            contentType = "text/html"
            isEditable = false
            background = UIUtil.getTextFieldBackground()
            border = JBUI.Borders.empty(8)
            editorKit = HTMLEditorKit()
        }

        metadataPanel.add(JBScrollPane(metadataPane), BorderLayout.CENTER)

        // Setup changes panel with tree and toolbar
        setupChangesPanel()

        // Create splitter: changes on top, metadata on bottom
        splitter =
            OnePixelSplitter(true, 0.5f).apply {
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

        return ActionManager
            .getInstance()
            .createActionToolbar(
                "JujutsuCommitDetailsChangesToolbar",
                group,
                true
            ).apply {
                targetComponent = changesTree
            }
    }

    private fun setupTreeInteractions() {
        // Double-click to show diff
        changesTree.setDoubleClickHandler {
            changesTree.selectedChanges.firstOrNull()?.let { change ->
                showDiff(change)
                true
            } ?: false
        }

        // Enter key to show diff
        changesTree.setEnterKeyHandler {
            changesTree.selectedChanges.firstOrNull()?.let { change ->
                showDiff(change)
                true
            } ?: false
        }

        // Context menu
        changesTree.addMouseListener(
            object : PopupHandler() {
                override fun invokePopup(
                    comp: Component,
                    x: Int,
                    y: Int
                ) {
                    showContextMenu(comp, x, y)
                }
            }
        )
    }

    private fun showDiff(change: Change) {
        // Load content in background thread to avoid EDT blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            val beforePath = change.beforeRevision?.file
            val afterPath = change.afterRevision?.file
            val fileName = afterPath?.name ?: beforePath?.name ?: JujutsuBundle.message("diff.title.unknown")

            // Load revision content in background
            val beforeContent = change.beforeRevision?.content ?: ""
            val afterContent = change.afterRevision?.content ?: ""

            // Get parent and current change IDs for titles
            val entry = currentEntry
            val parentId = entry?.parentIds?.firstOrNull()?.short ?: "parent"
            val currentId = entry?.changeId?.short ?: "current"

            // Create diff UI on EDT with loaded content
            ApplicationManager.getApplication().invokeLater {
                val contentFactory = DiffContentFactory.getInstance()
                val diffManager = DiffManager.getInstance()

                val content1 =
                    if (beforePath != null && beforeContent.isNotEmpty()) {
                        contentFactory.create(project, beforeContent, beforePath.fileType)
                    } else {
                        contentFactory.createEmpty()
                    }

                val content2 =
                    if (afterPath != null && afterContent.isNotEmpty()) {
                        contentFactory.create(project, afterContent, afterPath.fileType)
                    } else {
                        contentFactory.createEmpty()
                    }

                val diffRequest =
                    SimpleDiffRequest(
                        fileName,
                        content1,
                        content2,
                        "${beforePath?.name ?: JujutsuBundle.message("diff.title.before")} ($parentId)",
                        "${afterPath?.name ?: JujutsuBundle.message("diff.title.after")} ($currentId)"
                    )

                diffManager.showDiff(project, diffRequest)
            }
        }
    }

    private fun openFile(change: Change) {
        val virtualFile = change.afterRevision?.file?.virtualFile
            ?: change.beforeRevision?.file?.virtualFile
            ?: return

        FileEditorManager.getInstance(project).openTextEditor(
            OpenFileDescriptor(project, virtualFile),
            true
        )
    }

    private fun showContextMenu(comp: Component, x: Int, y: Int) {
        val selectedChange = changesTree.selectedChanges.firstOrNull() ?: return

        val actionGroup = DefaultActionGroup().apply {
            add(
                object : DumbAwareAction(
                    JujutsuBundle.message("action.showdiff"),
                    null,
                    AllIcons.Actions.Diff
                ) {
                    override fun actionPerformed(e: AnActionEvent) {
                        showDiff(selectedChange)
                    }
                }
            )
            add(
                object : DumbAwareAction(
                    JujutsuBundle.message("action.openfile"),
                    null,
                    AllIcons.Actions.EditSource
                ) {
                    override fun actionPerformed(e: AnActionEvent) {
                        openFile(selectedChange)
                    }
                }
            )
        }

        val popupMenu = ActionManager.getInstance().createActionPopupMenu(
                "JujutsuCommitDetailsChangesContextMenu",
                actionGroup
            )

        popupMenu.component.show(comp, x, y)
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
            metadataPane.caretPosition = 0
        }

        // Load changes in background
        loadChanges(entry)
    }

    private fun loadChanges(entry: LogEntry) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val fullDetails = JujutsuFullCommitDetails.create(entry, root)
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
                log.error("Failed to load changes for ${entry.changeId}", e)
                ApplicationManager.getApplication().invokeLater {
                    if (currentEntry == entry) {
                        changesTree.setChangesToDisplay(emptyList())
                    }
                }
            }
        }
    }

    /**
     * Build HTML for commit details, matching Git plugin style.
     */
    private fun buildCommitHtml(entry: LogEntry): String {
        val sb = StringBuilder()
        val canvas = StringBuilderHtmlTextCanvas(sb)
        sb.append("<html><body style='${Formatters.getBodyStyle()}'>")

        // Commit line: Change ID + decorations
        sb.append("<p style='margin: 0; padding-bottom: 4px;'>")

        // Change ID using standard formatter
        canvas.append(entry.changeId)

        // Bookmarks with branch icon (⎇ symbol - JEditorPane doesn't support icon data URIs reliably)
        if (entry.bookmarks.isNotEmpty()) {
            sb.append(" ")
            entry.bookmarks.forEach { bookmark ->
                sb.append(
                    "<span style='color: #${JujutsuColors.getBookmarkHex()};'>\u2387 ${
                        Formatters.escapeHtml(bookmark.name)
                    }</span> "
                )
            }
        }

        // Status flags
        val statusParts = mutableListOf<String>()
        if (entry.isWorkingCopy) {
            statusParts.add(
                "<span style='color: #${JujutsuColors.getWorkingCopyHex()};'>@ ${
                    JujutsuBundle.message(
                        "status.workingcopy"
                    )
                }</span>"
            )
        }
        if (entry.hasConflict) {
            statusParts.add(
                "<span style='color: #${JujutsuColors.getConflictHex()};'>${
                    JujutsuBundle.message(
                        "status.conflict"
                    )
                }</span>"
            )
        }
        if (entry.isEmpty) {
            statusParts.add(JujutsuBundle.message("status.empty"))
        }

        if (statusParts.isNotEmpty()) {
            sb.append(" [${statusParts.joinToString(", ")}]")
        }

        sb.append("</p>")

        // Description or (no description) in italics
        if (entry.description.empty) {
            sb.append("<p style='margin: 8px 0; font-style: italic; color: #${JujutsuColors.getGrayHex()};'>")
            sb.append(JujutsuBundle.message("description.empty"))
            sb.append("</p>")
        } else {
            sb.append("<p style='margin: 8px 0;'>")
            sb.append(Formatters.escapeHtml(entry.description.actual))
            sb.append("</p>")
        }

        // Author and committer info (Git style with formatted dates)
        sb.append("<p style='margin: 4px 0;'>")

        // Author line
        val authorName = entry.author?.name ?: "Unknown"
        val authorEmail = entry.author?.email ?: ""
        val authorTime = entry.authorTimestamp?.let { DateTimeFormatter.formatRelative(it) } ?: ""

        sb.append("$authorName ")
        if (authorEmail.isNotEmpty()) {
            sb.append(
                "<a href='mailto:$authorEmail' style='color: #${JujutsuColors.getLinkHex()};'>&lt;$authorEmail&gt;</a> "
            )
        }
        if (authorTime.isNotEmpty()) {
            sb.append("on $authorTime")
        }

        // Committer line (if different from author)
        val committerName = entry.committer?.name
        val committerEmail = entry.committer?.email
        val committerTime = entry.committerTimestamp

        if (committerName != null && committerName != authorName) {
            sb.append("<br/>committed by $committerName ")
            if (committerEmail != null && committerEmail.isNotEmpty()) {
                sb.append(
                    "<a href='mailto:$committerEmail' style='color: #${JujutsuColors.getLinkHex()};'>&lt;$committerEmail&gt;</a> "
                )
            }
            if (committerTime != null) {
                sb.append("on ${DateTimeFormatter.formatRelative(committerTime)}")
            }
        }

        sb.append("</p>")

        // Parents (normal color, using standard formatter)
        if (entry.parentIds.isNotEmpty()) {
            sb.append("<p style='margin: 4px 0;'>")
            sb.append("Parents: ")
            sb.append(
                entry.parentIds.joinToString(", ") {
                    htmlText { append(it) }
                }
            )
            sb.append("</p>")
        }

        // Commit ID (technical detail, smaller and grey)
        sb.append(
            "<p style='margin: 4px 0; color: #${JujutsuColors.getGrayHex()}; font-size: ${UIUtil.getLabelFont().size - 1}pt;'>"
        )
        sb.append("Commit: <span style='font-family: monospace;'>${entry.commitId}</span>")
        sb.append("</p>")

        sb.append("</body></html>")
        return sb.toString()
    }

    /**
     * Show empty state when no commit is selected.
     */
    private fun showEmptyState() {
        metadataPane.text = "<html><body style='${Formatters.getBodyStyle()} padding: 8px;'>" +
            "<i style='color: #${JujutsuColors.getGrayHex()};'>${
                JujutsuBundle.message(
                    "details.empty.message"
                )
            }</i>" +
            "</body></html>"
    }

    override fun dispose() {
        // Cleanup if needed
    }
}
