package `in`.kkkev.jjidea.ui.dnd

import com.intellij.util.ui.JBUI

/**
 * Schmitt-trigger thresholding for [DropZones] (design section 4): once a drag has settled into a
 * zone, the pointer must move [slack] pixels *past* the boundary before the zone switches, rather
 * than exactly at the boundary - the simplest of the pointing-facilitation techniques surveyed
 * there, and explicitly recommended to compose with any adaptive-zone scheme built later. Without
 * this, a pointer sitting a pixel either side of a ~5px band would flicker the drop indicator on
 * every sub-pixel jitter.
 *
 * One instance per drag gesture - [reset] on drag start/end, [update] on every mouse-move. Row
 * changes always take the raw (non-hysteresis) zone immediately: hysteresis is about *not*
 * flickering near a boundary *within* a row, not about carrying a stale zone across rows.
 */
class ZoneHysteresis(private val slack: Int = JBUI.scale(3)) {
    private var lastRow: Int? = null
    private var lastZone: DropZone? = null

    /**
     * Resolve row-relative offset [dy] (row height [rowHeight], edge band [band] - see
     * [DropZones.bandFor]) to a [DropZone], applying hysteresis against whatever zone [row] was
     * last resolved to.
     */
    fun update(row: Int, dy: Int, rowHeight: Int, band: Int): DropZone {
        val raw = DropZones.zoneAt(dy, rowHeight, band)
        val previous = lastZone.takeIf { row == lastRow }
        val resolved = if (previous == null || previous == raw) {
            raw
        } else {
            val crossedIntoRaw = when (raw) {
                DropZone.INSERT_BEFORE -> dy < band - slack
                DropZone.INSERT_AFTER -> dy >= rowHeight - band + slack
                DropZone.ONTO -> when (previous) {
                    DropZone.INSERT_BEFORE -> dy >= band + slack
                    DropZone.INSERT_AFTER -> dy < rowHeight - band - slack
                    DropZone.ONTO -> true
                }
            }
            if (crossedIntoRaw) raw else previous
        }
        lastRow = row
        lastZone = resolved
        return resolved
    }

    /** Clear held state - call at the start and end of every drag gesture. */
    fun reset() {
        lastRow = null
        lastZone = null
    }
}
