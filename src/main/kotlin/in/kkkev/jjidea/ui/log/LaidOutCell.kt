package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.*
import java.awt.Color
import java.awt.Font
import java.awt.font.FontRenderContext
import java.net.URI

/**
 * The graph+description column's content for one row, built once (via [forRow]) and shared by
 * painting ([JujutsuGraphAndDescriptionRenderer]) and hit-testing ([JujutsuLogTable.clickTargetAt]
 * and the renderer's own hover lookups) - replacing the several independent canvas rebuilds each
 * previously did on its own (jj-idea-91qf, jj-idea-vrmv, jj-idea-w61m).
 *
 * [leftFragments]/[rightFragments] are untruncated - a caller painting them (via
 * [in.kkkev.jjidea.ui.components.TruncatingLeftRightLayout]) still does its own truncation from
 * real Swing-measured widths; [linkTargetAt] instead truncates the left side itself with
 * [FragmentLayout] math, since hit-testing has no live component to measure.
 */
internal class LaidOutCell(
    val leftFragments: List<FragmentRecordingCanvas.Fragment>,
    val rightFragments: List<FragmentRecordingCanvas.Fragment>,
    val hidden: List<LogClickTarget>,
    private val textStartX: Int,
    private val columnWidth: Int,
    private val font: Font,
    private val frc: FontRenderContext
) {
    private val rightWidth = rightFragments.sumOf { FragmentLayout.fragmentWidth(it, font, frc) }

    /** Cell-relative x (from the column's left edge) where the right-aligned decorations begin. */
    private val rightStartX = columnWidth - rightWidth

    private val truncatedLeftFragments by lazy {
        val availableForLeft = (rightStartX - textStartX).coerceAtLeast(0.0)
        FragmentLayout.truncateToFit(leftFragments, truncateRangeOf(leftFragments), availableForLeft, font, frc)
    }

    /**
     * The link target under cell-relative pixel [localX] - the right-aligned decorations are
     * checked first, matching how they're painted flush to the cell's right edge, with the
     * (possibly truncated) description text checked only for an [localX] left of them.
     */
    fun linkTargetAt(localX: Int): URI? {
        if (localX >= rightStartX) return walk(rightFragments, rightStartX, localX)
        if (localX < textStartX) return null
        return walk(truncatedLeftFragments, textStartX.toDouble(), localX)
    }

    private fun walk(fragments: List<FragmentRecordingCanvas.Fragment>, startX: Double, localX: Int): URI? {
        var x = startX
        for (fragment in fragments) {
            val w = FragmentLayout.fragmentWidth(fragment, font, frc)
            if (localX < x + w) return fragment.linkTarget as? URI
            x += w
        }
        return null
    }

    private fun truncateRangeOf(fragments: List<FragmentRecordingCanvas.Fragment>): IntRange? {
        val first = fragments.indexOfFirst { it.truncatable }
        if (first == -1) return null
        return first..fragments.indexOfLast { it.truncatable }
    }

    companion object {
        /**
         * Build the cell content for [entry] once - the single place both painting and hit-testing get their
         * `entryCanvas`/`cappedDecorations` from, so neither has to rebuild what the other already built.
         *
         * @param columnWidth full column width in pixels (including the graph)
         * @param textStartX where the text area begins, i.e. past the graph (see `graphTextStartX`/`textStartX()`)
         */
        fun forRow(
            entry: LogEntry,
            columnWidth: Int,
            textStartX: Int,
            columnManager: JujutsuColumnManager,
            linkifier: Linkifier,
            fg: Color,
            font: Font,
            frc: FontRenderContext
        ): LaidOutCell {
            val leftCanvas = entryCanvas(entry, fg, linkifier) {
                if (columnManager.showStatus) appendStatusIndicators(entry)
                if (columnManager.showChangeId) {
                    append(entry.id)
                    append(" ")
                }
                if (columnManager.showDescription) {
                    appendDescriptionAndEmptyIndicator(entry)
                }
            }
            val decorations = if (columnManager.showDecorations) {
                cappedDecorations(entry, fg, columnWidth * DECORATION_WIDTH_FRACTION, font, frc, linkifier)
            } else {
                CappedDecorations(FragmentRecordingCanvas(), emptyList())
            }
            return LaidOutCell(
                leftCanvas.fragments,
                decorations.canvas.fragments,
                decorations.hidden,
                textStartX,
                columnWidth,
                font,
                frc
            )
        }
    }
}
