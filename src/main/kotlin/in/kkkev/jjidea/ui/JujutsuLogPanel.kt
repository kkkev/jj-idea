package `in`.kkkev.jjidea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.jj.JujutsuLogEntry
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

/**
 * Panel for displaying jj log view
 */
class JujutsuLogPanel(private val project: Project) : Disposable {

    private val log = Logger.getInstance(JujutsuLogPanel::class.java)
    private val panel = JPanel(BorderLayout())
    private val logTable: JBTable
    private val tableModel: DefaultTableModel
    private var logEntries: List<JujutsuLogEntry> = emptyList()

    private val vcs get() = JujutsuVcs.find(project)

    init {
        // Create table model with columns
        tableModel = object : DefaultTableModel(
            arrayOf("Change ID", "Bookmarks", "Description", "Commit ID"),
            0
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        logTable = JBTable(tableModel)
        logTable.setDefaultRenderer(Any::class.java, LogEntryRenderer())

        // Set column widths
        logTable.columnModel.getColumn(0).preferredWidth = 150 // Change ID
        logTable.columnModel.getColumn(1).preferredWidth = 120 // Bookmarks
        logTable.columnModel.getColumn(2).preferredWidth = 400 // Description
        logTable.columnModel.getColumn(3).preferredWidth = 150 // Commit ID

        setupTableInteractions()
        createUI()
        setupVcsListener()

        // Try to load immediately in case VCS is already available
        loadLog()
    }

    private fun setupVcsListener() {
        // Listen for VCS mapping changes to reload when Jujutsu VCS is enabled
        val connection = project.messageBus.connect(this)
        connection.subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, VcsListener {
            log.info("VCS configuration changed, reloading log")
            loadLog()
        })
    }

    private fun setupTableInteractions() {
        // Double-click on a row to show commit details (future: could show files changed)
        logTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    val row = logTable.rowAtPoint(e.point)
                    if (row >= 0 && row < logEntries.size) {
                        val entry = logEntries[row]
                        // For now, just log that double-click happened
                        // In future, could open commit details view or show files changed
                        println("Double-clicked commit: ${entry.changeId}")
                    }
                }
            }
        })
    }

    private fun createUI() {
        panel.border = JBUI.Borders.empty(8)

        // Toolbar
        val toolbar = createToolbar()
        toolbar.targetComponent = logTable  // Fix: Set target component to avoid warning
        panel.add(toolbar.component, BorderLayout.NORTH)

        // Table
        panel.add(ScrollPaneFactory.createScrollPane(logTable), BorderLayout.CENTER)
    }

    private fun createToolbar(): ActionToolbar {
        val group = DefaultActionGroup()

        // Refresh action
        group.add(object : DumbAwareAction("Refresh", "Refresh log", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refresh()
            }
        })

        return ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, group, true)
    }

    fun getContent(): JPanel = panel

    fun refresh() {
        loadLog()
    }

    private fun loadLog() {
        log.info("Loading Jujutsu log for project: ${project.name}")

        val vcsInstance = vcs
        if (vcsInstance == null) {
            log.info("VCS instance is null - Jujutsu VCS not yet initialized or not enabled for project")

            // Show user-friendly message in table
            ApplicationManager.getApplication().invokeLater {
                tableModel.rowCount = 0
                tableModel.addRow(
                    arrayOf(
                        "Waiting for VCS...",
                        "",
                        "Go to Settings → Version Control to enable Jujutsu VCS, then click Refresh",
                        ""
                    )
                )
            }
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            log.info("Loading log entries using JujutsuLogService")

            // Use logService to get log entries (templates and parsing encapsulated)
            val result = vcsInstance.logService.getLogBasic("all()")

            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { entries ->
                    log.info("Successfully loaded ${entries.size} log entries")
                    updateLogView(entries)
                }.onFailure { error ->
                    log.error("Failed to load log entries", error)
                    tableModel.rowCount = 0
                    tableModel.addRow(
                        arrayOf(
                            "Error loading log",
                            "",
                            error.message ?: "Unknown error",
                            ""
                        )
                    )
                }
            }
        }
    }

    private fun updateLogView(entries: List<JujutsuLogEntry>) {
        log.info("Updating log view with ${entries.size} entries")
        logEntries = entries

        // Clear existing rows
        tableModel.rowCount = 0

        // Add new rows
        for (entry in entries) {
            val changeIdDisplay = formatChangeId(entry)
            val bookmarks = entry.getBookmarkDisplay()
            val description = entry.getDisplayDescription()
            val commitId = entry.commitId.take(12) // Show short commit ID

            tableModel.addRow(arrayOf(changeIdDisplay, bookmarks, description, commitId))
        }

        log.info("Log view updated successfully with ${logEntries.size} rows")
    }

    private fun formatChangeId(entry: JujutsuLogEntry): String {
        val formatted = entry.getFormattedChangeId()
        val markers = entry.getMarkers()

        val markerStr = if (markers.isNotEmpty()) {
            " [${markers.joinToString(", ")}]"
        } else {
            ""
        }

        val workingCopyMarker = if (entry.isWorkingCopy) " @" else ""

        return "${formatted.shortPart}${formatted.restPart}$workingCopyMarker$markerStr"
    }

    /**
     * Custom renderer for log entries to show bold short prefix and markers
     */
    private inner class LogEntryRenderer : TableCellRenderer {
        private val label = JBLabel()

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val text = value?.toString() ?: ""

            // For Change ID column, use theme-aware colored formatting
            if (column == 0 && row < logEntries.size) {
                val entry = logEntries[row]
                val formatted = entry.getFormattedChangeId()
                val changeIdHtml = JujutsuCommitFormatter.toHtml(formatted)
                val markers = entry.getMarkers()

                val markerStr = if (markers.isNotEmpty()) {
                    " [${markers.joinToString(", ")}]"
                } else {
                    ""
                }

                val workingCopyMarker = if (entry.isWorkingCopy) " <b>@</b>" else ""

                label.text = "<html>$changeIdHtml$workingCopyMarker$markerStr</html>"
            } else {
                label.text = text
            }

            // Set selection colors
            if (isSelected) {
                label.background = table.selectionBackground
                label.foreground = table.selectionForeground
                label.isOpaque = true
            } else {
                label.background = table.background
                label.foreground = table.foreground
                label.isOpaque = false
            }

            label.font = table.font

            return label
        }
    }

    override fun dispose() {
        // Nothing to dispose
    }
}
