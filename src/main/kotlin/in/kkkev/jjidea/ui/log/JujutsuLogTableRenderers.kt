package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.util.ui.JBUI
import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.components.*
import java.awt.Color
import java.awt.Font
import java.awt.font.FontRenderContext
import java.net.URI
import kotlinx.datetime.Instant

/**
 * Renderer for the Author and Committer columns.
 */
class UserCellRenderer : TextTableCellRenderer<VcsUser>() {
    override fun render(value: VcsUser) {
        canvas.styled(if (isWorkingCopyRow) Font.BOLD else 0) {
            // Link color always (via append(VcsUser)'s linked()); underline only while this
            // exact cell's link is hovered (jj-idea-iesq) - see isHoveredLinkCell's doc.
            if (isHoveredLinkCell) underlined { append(value) } else append(value)
        }
        toolTipText = value.email
    }
}

/**
 * Renderer for the Date column.
 * Shows formatted date/time using consistent formatter (Today/Yesterday/localized date).
 */
class DateCellRenderer : TextTableCellRenderer<Instant>() {
    override fun render(value: Instant) {
        canvas.styled(if (isWorkingCopyRow) Font.BOLD else 0) { append(value) }

        // Tooltip shows full absolute time
        toolTipText = DateTimeFormatter.formatAbsolute(value)
    }
}

/**
 * Build a [FragmentRecordingCanvas] with the standard row styling (bold for working copy,
 * foreground color for selection state) applied around [builder]. [linkifier] is injected into the
 * canvas itself (jj-idea-91qf, jj-idea-vrmv) rather than threaded through every append call inside
 * [builder] - see [TextCanvas.linkifier]. [hoveredTarget], if any, is underlined afterwards (see
 * [underlining]) rather than baked in at append time.
 */
fun entryCanvas(
    entry: LogEntry,
    fg: Color,
    linkifier: Linkifier = Linkifier.None,
    hoveredTarget: URI? = null,
    builder: TextCanvas.() -> Unit
): FragmentRecordingCanvas {
    val canvas = FragmentRecordingCanvas(linkifier = linkifier).apply {
        foreground(fg) {
            styled(if (entry.isWorkingCopy) Font.BOLD else 0, builder)
        }
    }
    return FragmentRecordingCanvas(canvas.fragments.underlining(hoveredTarget))
}

/** Append status indicators: immutable/public icon and conflict warning. */
fun TextCanvas.appendStatusIndicators(entry: LogEntry) {
    smaller {
        if (entry.immutable) append(icon(JujutsuIcons::Immutable))
        appendConflict(entry)
    }
}

/** Append right-side decorations: bookmarks, tags, and working copy indicator. Uncapped — used by the
 * row tooltip and single-revision dialogs (Split/Squash/Rebase), where there is no width constraint
 * to collapse against. The capped, in-cell equivalent is [cappedDecorations]. */
fun TextCanvas.appendDecorations(entry: LogEntry) {
    appendBookmarks(entry)
    if (entry.tags.isNotEmpty()) {
        if (entry.bookmarks.isNotEmpty()) append(" ")
        appendTags(entry)
    }
    if (entry.isWorkingCopy) {
        append(" ")
        colored(JujutsuColors.WORKING_COPY) { bold { append(WorkingCopy.REF) } }
    }
}

/** Fraction of the graph+description column width that decorations (bookmarks/tags) may occupy
 * before the rest collapse behind a "+N more" chip (jj-idea-w61m), guaranteeing the description
 * always keeps at least the remaining share of the cell. */
internal const val DECORATION_WIDTH_FRACTION = 0.5

/** Result of [cappedDecorations]: the canvas to render, and the refs that were collapsed away. */
data class CappedDecorations(val canvas: FragmentRecordingCanvas, val hidden: List<LogClickTarget>)

/**
 * Build the right-side decoration canvas for [entry], capped to [maxWidth] pixels (typically
 * [DECORATION_WIDTH_FRACTION] of the column width) so a long bookmark list can never push the
 * description out of the cell (jj-idea-w61m).
 *
 * Bookmark and tag chips are kept left-to-right while they fit; any that don't fit are collapsed
 * into a single clickable "+N more" chip (a `kind=overflow` [refUri]) whose hit target opens a
 * popup over [CappedDecorations.hidden] — see `JujutsuLogTable.clickTargetAt`. The working-copy
 * `@` marker is never collapsed. The full, uncapped list remains available via the row tooltip
 * ([appendSummaryAndStatuses]/[appendDecorations]), so capping only narrows what's painted, never
 * what's discoverable.
 *
 * [linkifier] linkifies issue-tracker references inside a chip's own name (e.g. a bookmark named
 * `jira-123-fix-thing`), underlining the fragment matching [hoveredTarget] (jj-idea-vrmv) - see
 * [in.kkkev.jjidea.ui.components.appendUnbreakable].
 */
fun cappedDecorations(
    entry: LogEntry,
    fg: Color,
    maxWidth: Double,
    font: Font,
    frc: FontRenderContext,
    linkifier: Linkifier = Linkifier.None,
    hoveredTarget: URI? = null
): CappedDecorations {
    val units = bookmarkRefChips(entry) + tagRefChips(entry)

    fun widthOf(builder: TextCanvas.() -> Unit) =
        FragmentRecordingCanvas().apply(builder).fragments.sumOf { FragmentLayout.fragmentWidth(it, font, frc) }

    val separatorWidth = widthOf { append(" ") }
    val widths = units.map { widthOf(it.build) }

    fun fitCount(budget: Double): Int {
        var used = 0.0
        for ((i, w) in widths.withIndex()) {
            val next = used + (if (i > 0) separatorWidth else 0.0) + w
            if (next > budget) return i
            used = next
        }
        return units.size
    }

    var kept = fitCount(maxWidth)
    if (kept < units.size) {
        // Reserve room for the "+N more" chip (sized from this provisional hidden count), then
        // refit — the final chip below always uses the post-refit count, so the label is accurate
        // even if refitting changes how many chips fit.
        val overflowWidth = widthOf { overflowChip(entry, units.size - kept) }
        kept = fitCount(maxWidth - separatorWidth - overflowWidth)
    }

    val hiddenUnits = units.drop(kept)
    val canvas = entryCanvas(entry, fg, linkifier, hoveredTarget) {
        units.take(kept).forEachIndexed { i, unit ->
            if (i > 0) append(" ")
            unit.build(this)
        }
        if (hiddenUnits.isNotEmpty()) {
            if (kept > 0) append(" ")
            overflowChip(entry, hiddenUnits.size)
        }
        if (entry.isWorkingCopy) {
            if (kept > 0 || hiddenUnits.isNotEmpty()) append(" ")
            colored(JujutsuColors.WORKING_COPY) { bold { append(WorkingCopy.REF) } }
        }
    }

    val hidden = hiddenUnits.map { unit ->
        when (val ref = unit.ref) {
            is Bookmark -> BookmarkClick(entry.repo, entry, ref)
            is Tag -> TagClick(entry.repo, entry, ref)
            else -> error("Unexpected decoration ref type: $ref")
        }
    }
    return CappedDecorations(canvas, hidden)
}

/** Render the clickable "+N more" overflow chip for collapsed decorations (jj-idea-w61m). */
private fun TextCanvas.overflowChip(entry: LogEntry, hiddenCount: Int) {
    linked(refUri(entry, "overflow", "overflow")) {
        grey { smaller { append(JujutsuBundle.message("log.decorations.overflow", hiddenCount)) } }
    }
}

// Ref-chip and description-text hit-testing used to live here as findInlinedRefUri/
// findDescriptionLinkUri, each rebuilding its own entryCanvas/cappedDecorations independently of
// what JujutsuGraphAndDescriptionRenderer actually painted. Both are now LaidOutCell.linkTargetAt
// (jj-idea-alew), built once per row and shared by painting and hit-testing alike.

/**
 * Compute the x-offset where the description text area begins for [row], mirroring
 * [JujutsuGraphAndDescriptionRenderer]'s private `textStartX()` exactly, but without its
 * per-renderer-instance passthrough-lane cache (jj-idea-91qf) - this runs once per discrete mouse
 * click (via `JujutsuLogTable.clickTargetAt`), not on every cell repaint, so recomputing row
 * passthroughs here (O(rows)) doesn't hit the same hot path the render-time cache protects.
 */
internal fun graphTextStartX(row: Int, model: JujutsuLogTableModel, graphNodes: Map<ChangeId, GraphNode>): Int {
    val entry = model.getEntry(row) ?: return JujutsuGraphAndDescriptionRenderer.HORIZONTAL_PADDING.get()
    val graphNode = graphNodes[entry.id] ?: return JujutsuGraphAndDescriptionRenderer.HORIZONTAL_PADDING.get()
    val laneWidth = JujutsuGraphAndDescriptionRenderer.LANE_WIDTH.get()
    val horizontalPadding = JujutsuGraphAndDescriptionRenderer.HORIZONTAL_PADDING.get()

    val rowByChangeId = HashMap<ChangeId, Int>()
    for (r in 0 until model.rowCount) {
        model.getEntry(r)?.let { rowByChangeId[it.id] = r }
    }
    val activeLanes = mutableSetOf(graphNode.lane)
    for (r in 0 until row) {
        val prevEntry = model.getEntry(r) ?: continue
        val prevNode = graphNodes[prevEntry.id] ?: continue
        for ((parentId, lane) in prevNode.passthroughLanes) {
            val parentRow = rowByChangeId[parentId] ?: continue
            if (row in (r + 1) until parentRow) activeLanes.add(lane)
        }
        if (prevEntry.parentIds.contains(entry.id)) activeLanes.add(prevNode.lane)
    }
    for (parentLane in graphNode.parentLanes) {
        if (parentLane != graphNode.lane) activeLanes.add(parentLane)
    }

    val rightmostLane = activeLanes.maxOrNull() ?: graphNode.lane
    return horizontalPadding + (rightmostLane + 1) * laneWidth
}

/**
 * Left inset before the author/committer name text within its cell (matches
 * [com.intellij.ui.SimpleColoredComponent]'s default `ipad` of `JBInsets.create(1,2)`, i.e. 2px
 * before the first fragment — see the same allowance in [in.kkkev.jjidea.ui.components.TextListCellRenderer]).
 */
private val PERSON_CELL_LEFT_INSET = JBUI.scale(2).toDouble()

/**
 * If [localX] falls within the rendered author/committer name for [entry] in [column] (a
 * [JujutsuLogTableModel.COLUMN_AUTHOR] or [JujutsuLogTableModel.COLUMN_COMMITTER]), return the
 * matching [PersonClick]; otherwise null. Deliberately narrower than the whole cell — clicking
 * blank cell space must not launch the mail client (jj-idea-iesq). Bold matches the working-copy
 * row styling applied by [UserCellRenderer].
 */
internal fun findPersonClickTarget(
    entry: LogEntry,
    column: Int,
    localX: Int,
    font: Font,
    frc: FontRenderContext
): PersonClick? {
    val (user, canFilter) = when (column) {
        JujutsuLogTableModel.COLUMN_AUTHOR -> (entry.author ?: return null) to true
        JujutsuLogTableModel.COLUMN_COMMITTER -> ((entry.committer ?: entry.author) ?: return null) to false
        else -> return null
    }
    if (user.email.isBlank()) return null
    val rowFont = if (entry.isWorkingCopy) font.deriveFont(Font.BOLD) else font
    val nameWidth = rowFont.getStringBounds(user.name, frc).width
    if (localX < PERSON_CELL_LEFT_INSET || localX > PERSON_CELL_LEFT_INSET + nameWidth) return null
    return PersonClick(entry.repo, entry, user, canFilter)
}

/**
 * Default column widths (sensible defaults that can be overridden by user preferences).
 */
private val DEFAULT_COLUMN_WIDTHS = mapOf(
    JujutsuLogTableModel.KEY_ROOT_GUTTER to 8,
    JujutsuLogTableModel.KEY_GRAPH_AND_DESCRIPTION to 600,
    JujutsuLogTableModel.KEY_AUTHOR to 100,
    JujutsuLogTableModel.KEY_COMMITTER to 100,
    JujutsuLogTableModel.KEY_DATE to 120
)

/**
 * Minimum widths used both as hard [javax.swing.table.TableColumn] floors and as the shrink
 * targets for [fitColumnWidths] when "Fit columns to window width" is on (jj-idea-lzq7). Lower
 * than the historical minimums (80/80/70) so the fixed columns can collapse further - down to an
 * ellipsized name/date - before a horizontal scrollbar becomes necessary.
 */
private val DESC_MIN_WIDTH = JBUI.scale(180)
private val AUTHOR_MIN_WIDTH = JBUI.scale(55)
private val COMMITTER_MIN_WIDTH = JBUI.scale(55)
private val DATE_MIN_WIDTH = JBUI.scale(60)

/**
 * Install all custom renderers on the given table.
 * Note: Combined graph+description renderer is installed separately when graph data is loaded.
 * Only installs renderers for columns that are actually present in the column model.
 */
fun JujutsuLogTable.installRenderers() {
    val rootGutterRenderer = JujutsuRootGutterRenderer()
    val authorRenderer = UserCellRenderer()
    val committerRenderer = UserCellRenderer()
    val dateRenderer = DateCellRenderer()

    for (i in 0 until columnModel.columnCount) {
        val column = columnModel.getColumn(i)
        val modelIndex = column.modelIndex
        val defaultWidth = DEFAULT_COLUMN_WIDTHS[JujutsuLogTableModel.columnKey(modelIndex)] ?: 100

        when (modelIndex) {
            JujutsuLogTableModel.COLUMN_ROOT_GUTTER -> {
                column.cellRenderer = rootGutterRenderer
                val gutterWidth = if (isRootGutterExpanded) gutterExpandedWidth else gutterCollapsedWidth
                column.preferredWidth = gutterWidth
                column.width = gutterWidth
                column.minWidth = gutterWidth
                column.maxWidth = gutterWidth
            }
            JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION -> {
                column.cellRenderer = JujutsuGraphAndDescriptionRenderer(
                    graphNodes,
                    columnManager,
                    IssueLinkifier(IssueNavigationConfiguration.getInstance(project))
                )
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = DESC_MIN_WIDTH
                column.maxWidth = Int.MAX_VALUE
            }
            JujutsuLogTableModel.COLUMN_AUTHOR -> {
                column.cellRenderer = authorRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = AUTHOR_MIN_WIDTH
            }
            JujutsuLogTableModel.COLUMN_COMMITTER -> {
                column.cellRenderer = committerRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = COMMITTER_MIN_WIDTH
            }
            JujutsuLogTableModel.COLUMN_DATE -> {
                column.cellRenderer = dateRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = DATE_MIN_WIDTH
            }
        }
    }
    // Columns just got (re)installed at their default/desired widths - re-fit against the
    // current viewport (e.g. after a column-visibility toggle) rather than waiting for the
    // next resize event.
    applyColumnWidthPolicy()
}
