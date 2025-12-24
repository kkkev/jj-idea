package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.Disposable
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.*
import java.awt.BorderLayout
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
class JujutsuCommitDetailsPanel : JPanel(BorderLayout()), Disposable {

    private val metadataPanel = JPanel(BorderLayout())
    private val changesPanel = JPanel(BorderLayout()) // Placeholder for file tree
    private val splitter: OnePixelSplitter

    // Metadata components
    private val metadataPane = JEditorPane()

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

        // Placeholder for changes panel
        changesPanel.add(JBLabel("Changed files tree (coming soon)").apply {
            border = JBUI.Borders.empty(8)
            foreground = UIUtil.getLabelDisabledForeground()
        }, BorderLayout.CENTER)

        // Create splitter: changes on top, metadata on bottom
        splitter = OnePixelSplitter(true, 0.5f).apply {
            firstComponent = changesPanel
            secondComponent = metadataPanel
        }

        add(splitter, BorderLayout.CENTER)

        // Show empty state initially
        showEmptyState()
    }

    /**
     * Update the panel to show details for the given commit.
     */
    fun showCommit(entry: LogEntry?) {
        if (entry == null) {
            showEmptyState()
            return
        }

        val html = buildCommitHtml(entry)
        metadataPane.text = html

        // Scroll to top
        SwingUtilities.invokeLater {
            metadataPane.caretPosition = 0
        }
    }

    /**
     * Build HTML for commit details, matching Git plugin style.
     */
    private fun buildCommitHtml(entry: LogEntry): String {
        val sb = StringBuilder()
        val canvas = StringBuilderHtmlTextCanvas(
            sb
        )
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
            statusParts.add("<span style='color: #${JujutsuColors.getWorkingCopyHex()};'>@ ${JujutsuBundle.message("status.workingcopy")}</span>")
        }
        if (entry.hasConflict) {
            statusParts.add("<span style='color: #${JujutsuColors.getConflictHex()};'>${JujutsuBundle.message("status.conflict")}</span>")
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
            sb.append("<a href='mailto:$authorEmail' style='color: #${JujutsuColors.getLinkHex()};'>&lt;$authorEmail&gt;</a> ")
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
                sb.append("<a href='mailto:$committerEmail' style='color: #${JujutsuColors.getLinkHex()};'>&lt;$committerEmail&gt;</a> ")
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
            sb.append(entry.parentIds.joinToString(", ") {
                htmlText { append(it) }
            })
            sb.append("</p>")
        }

        // Commit ID (technical detail, smaller and grey)
        sb.append("<p style='margin: 4px 0; color: #${JujutsuColors.getGrayHex()}; font-size: ${UIUtil.getLabelFont().size - 1}pt;'>")
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
                "<i style='color: #${JujutsuColors.getGrayHex()};'>${JujutsuBundle.message("details.empty.message")}</i>" +
                "</body></html>"
    }

    override fun dispose() {
        // Cleanup if needed
    }
}
