package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.log.graph.ParentState

/**
 * The parents that the visible [rows] draw a faint "not loaded" stub for (jj-idea-ytvh): what paging
 * could still bring in. [ParentState.HIDDEN] stubs are skipped - a filtered-out parent never loads.
 * O([rows]) - the viewport, not the log.
 */
internal fun stubParentsToLoad(
    rows: IntRange,
    entryAt: (Int) -> LogEntry?,
    graphNodes: Map<ChangeKey, GraphNode>
): Set<ChangeKey> {
    val result = LinkedHashSet<ChangeKey>()
    for (row in rows) {
        val node = graphNodes[entryAt(row)?.key ?: continue] ?: continue
        for ((parent, state) in node.unresolvedParents) {
            if (state == ParentState.NOT_LOADED) result += parent
        }
    }
    return result
}
