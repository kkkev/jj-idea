package `in`.kkkev.jjidea.ui.log

import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.components.*
import kotlinx.datetime.Instant
import java.awt.Color
import java.awt.Font
import java.awt.font.FontRenderContext
import java.net.URI

/**
 * Renderer for the Author and Committer columns.
 */
class UserCellRenderer : TextTableCellRenderer<VcsUser>() {
    override fun render(value: VcsUser) {
        canvas.styled(if (isWorkingCopyRow) Font.BOLD else 0) { append(value) }
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
 * foreground color for selection state) applied around [builder].
 */
fun entryCanvas(entry: LogEntry, fg: Color, builder: TextCanvas.() -> Unit) = FragmentRecordingCanvas().apply {
    foreground(fg) {
        styled(if (entry.isWorkingCopy) Font.BOLD else 0, builder)
    }
}

/** Append status indicators: immutable/public icon and conflict warning. */
fun TextCanvas.appendStatusIndicators(entry: LogEntry) {
    smaller {
        append(icon(if (entry.immutable) JujutsuIcons::Immutable else JujutsuIcons::Mutable))
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
 */
fun cappedDecorations(
    entry: LogEntry,
    fg: Color,
    maxWidth: Double,
    font: Font,
    frc: FontRenderContext
): CappedDecorations {
    val units = bookmarkDecorationUnits(entry) + tagDecorationUnits(entry)

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
    val canvas = entryCanvas(entry, fg) {
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

/**
 * Compute the ref chip hit at [localX] within the inlined decorations of the graph-description column.
 *
 * The decorations are rendered on the right side of the cell, starting at `colWidth - rightWidth`,
 * capped to [DECORATION_WIDTH_FRACTION] of [colWidth] (jj-idea-w61m). We measure fragment widths from
 * the canvas to locate the target without Swing layout.
 *
 * @param entry   the log entry for the row being hit-tested
 * @param localX  x position relative to the left edge of the full cell
 * @param colWidth full column width in pixels
 * @param font    base font of the table
 * @param frc     font render context from the table
 * @param showDecorations whether the decoration inlining is enabled
 */
internal fun findInlinedRefUri(
    entry: LogEntry,
    localX: Int,
    colWidth: Int,
    font: Font,
    frc: FontRenderContext,
    showDecorations: Boolean
): URI? {
    if (!showDecorations) return null
    val rightCanvas = cappedDecorations(entry, Color.BLACK, colWidth * DECORATION_WIDTH_FRACTION, font, frc).canvas
    val rightWidth = rightCanvas.fragments.sumOf { FragmentLayout.fragmentWidth(it, font, frc) }
    val rightStart = colWidth - rightWidth
    if (localX < rightStart) return null
    var xAccum = 0.0
    val xInRight = localX - rightStart
    for (fragment in rightCanvas.fragments) {
        val w = FragmentLayout.fragmentWidth(fragment, font, frc)
        if (xAccum + w > xInRight) return fragment.linkTarget as? URI
        xAccum += w
    }
    return null
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
                column.cellRenderer = JujutsuGraphAndDescriptionRenderer(graphNodes, columnManager)
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.maxWidth = Int.MAX_VALUE
            }
            JujutsuLogTableModel.COLUMN_AUTHOR -> {
                column.cellRenderer = authorRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = 80
            }
            JujutsuLogTableModel.COLUMN_COMMITTER -> {
                column.cellRenderer = committerRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = 80
            }
            JujutsuLogTableModel.COLUMN_DATE -> {
                column.cellRenderer = dateRenderer
                column.preferredWidth = defaultWidth
                column.width = defaultWidth
                column.minWidth = 70
            }
        }
    }
}
