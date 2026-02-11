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
    val passthroughLanes: Map<I, Int> = emptyMap()
)
