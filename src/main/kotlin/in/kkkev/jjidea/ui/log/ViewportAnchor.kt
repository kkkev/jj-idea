package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeKey

/**
 * The identity of the row sitting at (or just above) the top of the viewport, plus how far it is
 * scrolled off-screen - captured before a data refresh so [JujutsuLogTable.setEntries] can restore
 * the same pixel relationship after the model swap, even though the refreshed row's index may have
 * shifted (jj-idea-wrza: `refresh()`'s page-1 splice can insert/remove rows above it).
 */
internal data class ViewportAnchor(val key: ChangeKey, val pixelsScrolledOff: Int)

/** Pure capture/restore arithmetic for [ViewportAnchor], kept out of Swing so it's unit-testable
 * without a platform test - same split as [GraphEdgeIndex] from jj-idea-sc8m. */
internal object ViewportAnchors {
    /**
     * Captures the anchor for the row at [topRowY] (its cell's top y in table coordinates) given
     * the viewport's current top-of-content y, [viewY], and that row's [key].
     *
     * Returns `null` when [key] is `null` (no row at the viewport top - e.g. an empty table), or
     * when [viewY] is `0`: the viewport is already pinned to the very top, and a commit newly
     * spliced in above row 0 should become visible, not scrolled past to preserve the old top
     * row's position.
     */
    fun capture(key: ChangeKey?, viewY: Int, topRowY: Int): ViewportAnchor? {
        if (key == null || viewY <= 0) return null
        return ViewportAnchor(key, viewY - topRowY)
    }

    /**
     * The viewport y that restores [anchor]'s pixel relationship, given the anchor row's new cell
     * top y ([newRowY]) after the model update. Clamped to `>= 0` since the row may have moved to
     * (or near) the top, where the old offset would otherwise scroll past the top of the content.
     */
    fun restoredY(anchor: ViewportAnchor, newRowY: Int): Int = (newRowY + anchor.pixelsScrolledOff).coerceAtLeast(0)
}
