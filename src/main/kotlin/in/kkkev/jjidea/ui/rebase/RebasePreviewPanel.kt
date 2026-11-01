package `in`.kkkev.jjidea.ui.rebase

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.components.installIconAwareTableTooltip
import `in`.kkkev.jjidea.ui.log.CommitGraphBuilder
import `in`.kkkev.jjidea.ui.log.GraphNode
import `in`.kkkev.jjidea.ui.log.JujutsuLogTableModel
import `in`.kkkev.jjidea.ui.log.hideAllButGraphColumn
import `in`.kkkev.jjidea.ui.log.setGraphRenderer
import `in`.kkkev.jjidea.ui.squash.SquashSimulation
import `in`.kkkev.jjidea.ui.squash.SquashSimulator
import `in`.kkkev.jjidea.util.LatestWinsGate
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Preview panel showing a simulated post-rebase (or post-squash) commit graph.
 *
 * Source commits are highlighted green, destination commits blue.
 * Below the graph, a text summary describes the operation.
 *
 * Driven entirely by [setEntries] and [update] (rebase) / [updateSquash] calls — no data in
 * constructor. Both funnel into [render], so the two simulators share one panel.
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
    private val gate = LatestWinsGate()
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
     * The simulation and graph layout run on a pooled thread (jj-idea-v6w9) - both are
     * O(entries) and froze the EDT on huge repos. A "Updating preview…" label shows meanwhile;
     * only the latest call's result is applied, so a superseded computation is discarded.
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
        val entries = allEntries
        val token = gate.next()
        descriptionLabel.text = JujutsuBundle.message("dialog.rebase.preview.loading")
        // ModalityState.any(): this runs from a modal dialog, and the default modality would
        // defer the runLater callback until the dialog closes.
        runInBackground(ModalityState.any()) {
            val simulation = RebaseSimulator.simulate(
                entries,
                sourceEntries,
                destinationIds,
                sourceMode,
                destinationMode
            )
            val nodes = buildHighlightedGraph(simulation.entries, simulation.sourceIds, destinationIds)
            val summary = rebaseSummary(
                entries,
                simulation.sourceIds,
                destinationIds,
                sourceMode,
                destinationMode,
                sourceLabel
            )
            runLater {
                if (gate.isCurrent(token)) show(simulation.entries, nodes, summary)
            }
        }
    }

    /**
     * Update the preview by simulating `jj squash` of [sources] into [destination] and rebuilding
     * the graph. [abandonSources] mirrors the dialog's "delete empty source" option.
     */
    fun updateSquash(sources: List<LogEntry>, destination: LogEntry?, abandonSources: Boolean) {
        gate.next() // supersede any in-flight rebase update
        val simulation = SquashSimulator.simulate(allEntries, sources, destination, abandonSources)
        val destinationIds = setOfNotNull(simulation.destinationId)
        render(
            simulation.entries,
            simulation.sourceIds,
            destinationIds,
            squashSummary(sources, simulation, destination)
        )
    }

    /**
     * Render [entries] as the preview graph, highlighting [sourceIds] green and [destinationIds]
     * blue, with [summaryHtml] (an `<html>` string, or empty) below. The common tail of
     * [update] and [updateSquash].
     */
    fun render(
        entries: List<LogEntry>,
        sourceIds: Set<ChangeId>,
        destinationIds: Set<ChangeId>,
        summaryHtml: String
    ) {
        show(entries, buildHighlightedGraph(entries, sourceIds, destinationIds), summaryHtml)
    }

    private fun buildHighlightedGraph(
        entries: List<LogEntry>,
        sourceIds: Set<ChangeId>,
        destinationIds: Set<ChangeId>
    ) = CommitGraphBuilder().buildGraph(entries).mapValues { (key, node) ->
        when (key.revision) {
            in sourceIds -> node.copy(highlightColor = JujutsuColors.SOURCE_HIGHLIGHT)
            in destinationIds -> node.copy(highlightColor = JujutsuColors.DESTINATION_HIGHLIGHT)
            else -> node
        }
    }

    /** EDT-only: installs already-computed rows and graph. */
    private fun show(entries: List<LogEntry>, nodes: Map<ChangeKey, GraphNode>, summaryHtml: String) {
        tableModel.setEntries(entries)
        graphNodes = nodes
        table.setGraphRenderer(graphNodes)
        descriptionLabel.text = summaryHtml
    }

    /** Ids of the rows currently rendered, in display order. */
    @org.jetbrains.annotations.TestOnly
    internal fun renderedIds(): List<ChangeId> = (0 until tableModel.rowCount).mapNotNull {
        tableModel.getEntry(it)?.id
    }

    private fun legend() = buildString {
        append("<font color='${colorHex(JujutsuColors.SOURCE_HIGHLIGHT)}'>&#9632;</font> source &nbsp; ")
        append(
            "<font color='${colorHex(JujutsuColors.DESTINATION_HIGHLIGHT)}'>&#9632;</font> destination &nbsp;&nbsp; "
        )
    }

    private fun squashSummary(sources: List<LogEntry>, simulation: SquashSimulation, destination: LogEntry?): String {
        if (sources.isEmpty() || destination == null) return ""
        val sourceText = if (sources.size == 1) sources.first().id.short else "${sources.size} changes"
        val outcome = if (simulation.abandonedIds.isEmpty()) "source kept" else "source abandoned"
        val destText = findDisplayName(allEntries, destination.id)
        return "<html>${legend()}$sourceText → squash into $destText ($outcome)</html>"
    }

    private fun rebaseSummary(
        entries: List<LogEntry>,
        sourceIds: Set<ChangeId>,
        destinationIds: Set<ChangeId>,
        sourceMode: RebaseSourceMode,
        destinationMode: RebaseDestinationMode,
        sourceLabel: String? = null
    ): String {
        if (sourceIds.isEmpty() || destinationIds.isEmpty()) {
            return ""
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

        val destText = destinationIds.joinToString(", ") { findDisplayName(entries, it) }

        val operationText = when (destinationMode) {
            RebaseDestinationMode.ONTO -> "onto $destText"
            RebaseDestinationMode.INSERT_AFTER -> "insert after $destText"
            RebaseDestinationMode.INSERT_BEFORE -> "insert before $destText"
        }

        return "<html>${legend()}$sourceText$modeText → $operationText</html>"
    }

    private fun findDisplayName(entries: List<LogEntry>, id: ChangeId): String {
        val entry = entries.find { it.id == id }
        return if (entry != null && entry.bookmarks.isNotEmpty()) {
            entry.bookmarks.first().name.name
        } else {
            id.short
        }
    }

    private fun colorHex(color: Color) = String.format("#%06x", color.rgb and 0xFFFFFF)
}
