package `in`.kkkev.jjidea.ui.log

import com.intellij.util.ui.JBUI
import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.jj.LogEntry
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

/** Append right-side decorations: bookmarks, tags, and working copy indicator. */
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

/**
 * Compute the ref chip hit at [localX] within the inlined decorations of the graph-description column.
 *
 * The decorations are rendered on the right side of the cell, starting at `colWidth - rightWidth`.
 * We measure fragment widths from the canvas to locate the target without Swing layout.
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
    val rightCanvas = entryCanvas(entry, Color.BLACK) { appendDecorations(entry) }
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
                val gutterWidth = JBUI.scale(8)
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
