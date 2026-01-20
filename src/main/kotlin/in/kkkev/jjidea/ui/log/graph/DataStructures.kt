package `in`.kkkev.jjidea.ui.log.graph

import `in`.kkkev.jjidea.jj.ChangeId

// === Input ===

data class GraphEntry(
    val current: ChangeId,
    /** Order matters: first parent is typically main branch */
    val parents: List<ChangeId>
)

// === Output ===

data class GraphLayout(
    val rows: List<RowLayout>
)

data class RowLayout(
    val changeId: ChangeId,
    val lane: Int,
    /** lanes of children (rows above with this as parent) */
    val childLanes: List<Int>,
    /** lanes of parents (rows below) */
    val parentLanes: List<Int>,
    /** lanes with vertical passthroughs */
    val passthroughLanes: Set<Int>
)
