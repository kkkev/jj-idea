package `in`.kkkev.jjidea.ui.dnd

import com.intellij.util.ui.JBUI
import kotlin.math.min

/**
 * The row-zone geometry from `docs/design/jj-idea-6oeg-drag-and-drop-graph-ops.md` section 2: a
 * three-way vertical split of each row, centre half -> [DropZone.ONTO], top/bottom quarter ->
 * [DropZone.INSERT_BEFORE]/[DropZone.INSERT_AFTER]. ONTO gets the largest share deliberately - it
 * is overwhelmingly the common operation, so a naive thirds-split would make the most frequent
 * action the hardest to hit.
 *
 * Kept pure and free of any Swing component state (only [bandFor] touches [JBUI]) so it unit-tests
 * without `@TestApplication`, matching [in.kkkev.jjidea.ui.log.LaidOutCell]'s split between pure
 * geometry and the renderer/table that feeds it real pixels.
 */
object DropZones {
    /**
     * The edge-band height in pixels for a row of [rowHeight]: a scaled 6px, clamped so the centre
     * ONTO band never drops below half the row even at very small row heights (design section 2's
     * worked example: at the 22px default, a literal quarter would be ~5.5px).
     */
    fun bandFor(rowHeight: Int): Int = min(JBUI.scale(6), rowHeight / 4)

    /**
     * The zone containing row-relative y-offset [dy], for a row of height [rowHeight] with edge
     * band [band] (from [bandFor] - passed in rather than recomputed so callers can hold it fixed
     * for a whole drag, and so tests can probe boundary values directly without going through
     * [JBUI] scaling).
     */
    fun zoneAt(dy: Int, rowHeight: Int, band: Int): DropZone = when {
        dy < band -> DropZone.INSERT_BEFORE
        dy >= rowHeight - band -> DropZone.INSERT_AFTER
        else -> DropZone.ONTO
    }
}
