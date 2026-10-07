package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.application.ApplicationManager
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings
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
         * @param budget fixed pixel widths for the status-icon and change-id slots (see [LogRowBudget.of])
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
            frc: FontRenderContext,
            budget: LogRowBudget = LogRowBudget.NONE
        ): LaidOutCell {
            val leftCanvas = entryCanvas(entry, fg, linkifier) {
                if (entry.pending) {
                    // A pending entry (see LogEntry.pending) has no real id/bookmarks/tags/status
                    // to show - paint only its in-progress description, never the real-commit
                    // fields below.
                    appendPendingSummary(entry)
                } else {
                    if (columnManager.showStatus) appendStatusSlots(entry, budget)
                    if (columnManager.showChangeId) {
                        append(entry.id)
                        append(" ")
                        // Pad the id run (separator included, so a bold row's wider space is absorbed)
                        // to the shared budget so descriptions line up (jj-idea-t04a).
                        if (budget.id > 0) gap(budget.id - changeIdRunWidth(entry, font, frc))
                    }
                    if (columnManager.showDescription) {
                        appendDescriptionAndEmptyIndicator(entry)
                    }
                }
            }
            val decorations = if (columnManager.showDecorations && !entry.pending) {
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

        /**
         * Pixel width of [entry]'s rendered change id plus its trailing separator space, in the row's
         * own styling (bold for the working copy).
         */
        internal fun changeIdRunWidth(entry: LogEntry, font: Font, frc: FontRenderContext): Double =
            entryCanvas(entry, Color.BLACK) {
                append(entry.id)
                append(" ")
            }.fragments
                .sumOf { FragmentLayout.fragmentWidth(it, font, frc) }
    }
}

/**
 * Whether the log pads status icons and change ids to fixed widths (jj-idea-t04a, GitHub #91).
 * Read live so the View Options toggle applies without reinstalling renderers; off when there is
 * no Application (plain unit tests), matching the setting's default.
 */
internal fun alignLogColumns(): Boolean = ApplicationManager.getApplication()
    ?.let { JujutsuApplicationSettings.getInstance().state.alignLogColumns } ?: false

/**
 * Fixed pixel widths that every row's status-icon run and change-id run are padded to, so the
 * icons, the id and the description each start at one x across rows (jj-idea-t04a). The status
 * slot is the widest status run among the loaded entries - one icon wide when no commit is both
 * immutable and conflicted, two when one is, none when no entry shows either - so the common case
 * stays tight while the rare both-icons row still has room.
 *
 * [immutableIconWidth]/[conflictIconWidth] are carried so a row can compute its own padding
 * without re-measuring.
 */
internal data class LogRowBudget(
    val statusSlot: Double,
    val immutableIconWidth: Double,
    val conflictIconWidth: Double,
    val id: Double
) {
    /** Width of [entry]'s own status icons. */
    fun statusWidthOf(entry: LogEntry) =
        (if (entry.immutable) immutableIconWidth else 0.0) + (if (entry.hasConflict) conflictIconWidth else 0.0)

    companion object {
        val NONE = LogRowBudget(0.0, 0.0, 0.0, 0.0)

        /**
         * One O(entries) pass: the widest id run plus which status-icon combinations appear (the
         * two icon widths are measured once, not per entry). Callers cache it per graph update.
         * [measureId] is a seam for operation-count tests.
         */
        fun of(
            entries: List<LogEntry>,
            font: Font,
            frc: FontRenderContext,
            measureId: (LogEntry, Font, FontRenderContext) -> Double = LaidOutCell.Companion::changeIdRunWidth
        ): LogRowBudget {
            var id = 0.0
            var anyImmutable = false
            var anyConflict = false
            var anyBoth = false
            for (entry in entries) {
                if (entry.pending) continue
                id = maxOf(id, measureId(entry, font, frc))
                anyImmutable = anyImmutable || entry.immutable
                anyConflict = anyConflict || entry.hasConflict
                anyBoth = anyBoth || (entry.immutable && entry.hasConflict)
            }
            fun iconWidth(build: TextCanvas.() -> Unit) =
                FragmentRecordingCanvas().apply(build).fragments.sumOf { FragmentLayout.fragmentWidth(it, font, frc) }
            val immutableWidth = if (anyImmutable) iconWidth { appendImmutableIndicator() } else 0.0
            val conflictWidth = if (anyConflict) iconWidth { appendConflictIndicator() } else 0.0
            val statusSlot = if (anyBoth) immutableWidth + conflictWidth else maxOf(immutableWidth, conflictWidth)
            return LogRowBudget(statusSlot, immutableWidth, conflictWidth, id)
        }
    }
}

/**
 * Status icons padded out to [LogRowBudget.statusSlot]. With no slot (e.g. [LogRowBudget.NONE])
 * this is plain [appendStatusIndicators].
 */
private fun TextCanvas.appendStatusSlots(entry: LogEntry, budget: LogRowBudget) {
    appendStatusIndicators(entry)
    if (budget.statusSlot > 0) gap(budget.statusSlot - budget.statusWidthOf(entry))
}
