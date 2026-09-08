package `in`.kkkev.jjidea.ui.log.graph

// === Input ===

data class GraphEntry<I : Any>(
    val current: I,
    /** Order matters: first parent is typically main branch */
    val parents: List<I>
)

// === Output ===

data class GraphLayout<I : Any>(
    val rows: List<RowLayout<I>>
)

data class RowLayout<I : Any>(
    val id: I,
    val lane: Int,
    /** lanes of children (rows above with this as parent) */
    val childLanes: List<Int>,
    /** lanes of parents (rows below) */
    val parentLanes: List<Int>,
    /** For each non-adjacent parent, the passthrough lane used (parent ID → lane) */
    val passthroughLanes: Map<I, Int> = emptyMap(),
    /**
     * True if this entry has at least one parent id not present in the loaded entry set —
     * i.e. a real parent this row's history continues to, just not currently loaded (paged log
     * window boundary, or a bounded context/search expansion), as opposed to a true repository
     * root. Distinguishing the two is jj-idea-2c8k's rendering-correctness backstop: today
     * (`LayoutCalculator`'s `continue` for an absent parent) both render identically — no
     * connector at all. This flag lets a renderer show a pending/elided indicator instead; see
     * docs/design/jj-idea-2c8k-paged-log-loading.md § "Graph rendering at page/window
     * boundaries" — the actual paint treatment is a separate follow-up, not yet implemented.
     */
    val hasElidedParents: Boolean = false
)
