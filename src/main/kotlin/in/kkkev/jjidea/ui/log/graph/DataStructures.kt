package `in`.kkkev.jjidea.ui.log.graph

import `in`.kkkev.jjidea.jj.ChangeId

class Graph {
}

// === Input ===

data class GraphEntry(
    val current: ChangeId,
    val parents: Set<ChangeId>
)

// === Output ===

data class GraphLayout(
    val rows: List<RowLayout>
)

data class RowLayout(
    val changeId: ChangeId,
    val lane: Int,
    val childLanes: List<Int>,       // lanes of children (rows above with this as parent)
    val parentLanes: List<Int>,      // lanes of parents (rows below)
    val passthroughLanes: Set<Int>   // lanes with vertical passthroughs
)
