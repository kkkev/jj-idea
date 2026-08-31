package `in`.kkkev.jjidea.ui.rebase

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.components.installIconAwareTableTooltip
import `in`.kkkev.jjidea.ui.log.CommitGraphBuilder
import `in`.kkkev.jjidea.ui.log.GraphNode
import `in`.kkkev.jjidea.ui.log.JujutsuLogTableModel
import `in`.kkkev.jjidea.ui.log.hideAllButGraphColumn
import `in`.kkkev.jjidea.ui.log.setGraphRenderer
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Preview panel showing a simulated post-rebase commit graph.
 *
 * Source commits are highlighted green, destination commits blue.
 * Below the graph, a text summary describes the operation.
 *
 * Driven entirely by [setEntries] and [update] calls — no data in constructor.
 */
class RebasePreviewPanel(project: Project) : JPanel(BorderLayout()) {
    private val tableModel = JujutsuLogTableModel()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        tableHeader.isVisible = false
        rowHeight = JBUI.scale(22)
        setShowGrid(false)
        intercellSpacing = java.awt.Dimension(0, 0)
        isEnabled = false // Read-only preview
    }
    private val descriptionLabel = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
    }

    private var graphNodes: Map<ChangeKey, GraphNode> = emptyMap()
    private var allEntries: List<LogEntry> = emptyList()

    init {
        val scrollPane = JBScrollPane(table).apply { border = JBUI.Borders.empty() }
        add(scrollPane, BorderLayout.CENTER)
        add(descriptionLabel, BorderLayout.SOUTH)
        // Hide extra columns once — setEntries/fireTableDataChanged don't recreate them
        table.hideAllButGraphColumn()
        // Renders row tooltips (bookmark/tag chips) via IconAwareHtmlPane instead of a plain
        // Swing tooltip, which paints chip <img> markup as a broken image (jj-idea-2md7).
        installIconAwareTableTooltip(table, project)
    }

    /**
     * Set the full list of entries available for simulation.
     * Called once from the dialog when entries are loaded.
     */
    fun setEntries(entries: List<LogEntry>) {
        allEntries = entries
    }

    /**
     * Update the preview by simulating the rebase and rebuilding the graph.
     *
     * @param sourceLabel Override for the summary's source description (e.g. "the new change"),
     *   for callers whose "source" entries don't already exist as real commits - see
     *   [in.kkkev.jjidea.ui.newchange.NewChangeDialog], which simulates `jj new` by passing a
     *   synthetic, not-yet-created [LogEntry] as the source. `null` (default) keeps the normal
     *   short-id/"N changes" summary used for a real rebase source.
     */
    fun update(
        sourceEntries: List<LogEntry>,
        destinationIds: Set<ChangeId>,
        sourceMode: RebaseSourceMode,
        destinationMode: RebaseDestinationMode,
        sourceLabel: String? = null
    ) {
        val simulation = RebaseSimulator.simulate(
            allEntries,
            sourceEntries,
            destinationIds,
            sourceMode,
            destinationMode
        )

        tableModel.setEntries(simulation.entries)

        val baseNodes = CommitGraphBuilder().buildGraph(simulation.entries)
        graphNodes = baseNodes.mapValues { (key, node) ->
            when (key.revision) {
                in simulation.sourceIds -> node.copy(highlightColor = JujutsuColors.SOURCE_HIGHLIGHT)
                in simulation.destinationIds -> node.copy(highlightColor = JujutsuColors.DESTINATION_HIGHLIGHT)
                else -> node
            }
        }

        table.setGraphRenderer(graphNodes)
        updateDescription(simulation.sourceIds, destinationIds, sourceMode, destinationMode, sourceLabel)
    }

    private fun updateDescription(
        sourceIds: Set<ChangeId>,
        destinationIds: Set<ChangeId>,
        sourceMode: RebaseSourceMode,
        destinationMode: RebaseDestinationMode,
        sourceLabel: String? = null
    ) {
        if (sourceIds.isEmpty() || destinationIds.isEmpty()) {
            descriptionLabel.text = ""
            return
        }

        val sourceText = sourceLabel ?: if (sourceIds.size == 1) {
            sourceIds.first().short
        } else {
            "${sourceIds.size} changes"
        }

        val modeText = when (sourceMode) {
            RebaseSourceMode.REVISION -> ""
            RebaseSourceMode.SOURCE -> " and descendants"
            RebaseSourceMode.BRANCH -> " (whole branch)"
        }

        val destText = destinationIds.joinToString(", ") { findDisplayName(it) }

        val operationText = when (destinationMode) {
            RebaseDestinationMode.ONTO -> "onto $destText"
            RebaseDestinationMode.INSERT_AFTER -> "insert after $destText"
            RebaseDestinationMode.INSERT_BEFORE -> "insert before $destText"
        }

        descriptionLabel.text = buildString {
            append("<html>")
            append("<font color='${colorHex(JujutsuColors.SOURCE_HIGHLIGHT)}'>&#9632;</font> source &nbsp; ")
            append(
                "<font color='${colorHex(
                    JujutsuColors.DESTINATION_HIGHLIGHT
                )}'>&#9632;</font> destination &nbsp;&nbsp; "
            )
            append("$sourceText$modeText → $operationText")
            append("</html>")
        }
    }

    private fun findDisplayName(id: ChangeId): String {
        val entry = allEntries.find { it.id == id }
        return if (entry != null && entry.bookmarks.isNotEmpty()) {
            entry.bookmarks.first().name.name
        } else {
            id.short
        }
    }

    private fun colorHex(color: Color) = String.format("#%06x", color.rgb and 0xFFFFFF)
}
