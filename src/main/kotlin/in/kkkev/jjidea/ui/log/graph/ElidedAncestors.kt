package `in`.kkkev.jjidea.ui.log.graph

/**
 * Nearest-visible-ancestor remap for a filtered log (jj-idea-hlu3): when a filter hides intermediate
 * commits, each visible commit is re-pointed at the nearest *visible* ancestors reached through its
 * hidden parents, the way jj draws an indirect edge for elided revisions.
 *
 * Generic over the node id so it is testable without `LogEntry`. [visible] are the laid-out nodes;
 * [parentsOf] returns a node's parents, or null when the node isn't loaded at all (the walk can't see
 * past it); [isVisible] says whether a parent is in the visible set.
 *
 * Complexity: each hidden node is expanded exactly once (memoised, shared across all visible nodes) with
 * an explicit stack - no recursion, so a very long hidden chain can't overflow - and doesn't rely on
 * [parentsOf] being topologically ordered. O(nodes + edges) visits, plus the set unions of merge-rich
 * hidden regions (each bounded by the number of visible nodes; size 1 on linear history).
 */
class ElidedAncestors<I : Any>(
    visible: List<I>,
    private val parentsOf: (I) -> List<I>?,
    private val isVisible: (I) -> Boolean
) {
    /** Visible-node -> its parents after the remap; only for nodes with at least one hidden parent. */
    val effectiveParents: Map<I, List<I>>

    /** Visible-node -> the subset of [effectiveParents] reached through at least one hidden node. */
    val elided: Map<I, Set<I>>

    /** Node and edge visits performed - for the scale test. */
    var operationCount: Long = 0
        private set

    private val memo = HashMap<I, Set<I>>()

    init {
        val effective = HashMap<I, List<I>>()
        val elidedByNode = HashMap<I, Set<I>>()
        for (node in visible) {
            val parents = parentsOf(node) ?: continue
            operationCount++
            if (parents.all(isVisible)) continue
            val direct = parents.filterTo(HashSet(), isVisible)
            val result = LinkedHashSet<I>()
            val viaHidden = LinkedHashSet<I>()
            for (parent in parents) {
                operationCount++
                if (isVisible(parent)) {
                    result += parent
                    continue
                }
                val ancestors = visibleAncestorsOfHidden(parent)
                // A hidden parent with nothing visible above it stays as itself, so the layout still
                // classifies it HIDDEN and draws today's stub.
                if (ancestors.isEmpty()) {
                    result += parent
                    continue
                }
                for (ancestor in ancestors) {
                    if (result.add(ancestor) && ancestor !in direct) viaHidden += ancestor
                }
            }
            effective[node] = result.toList()
            if (viaHidden.isNotEmpty()) elidedByNode[node] = viaHidden
        }
        effectiveParents = effective
        elided = elidedByNode
    }

    /** The nearest visible ancestors above the hidden [start], memoised per hidden node. */
    private fun visibleAncestorsOfHidden(start: I): Set<I> {
        memo[start]?.let { return it }
        // Post-order DFS: a node resolves once all its hidden parents have.
        val stack = ArrayDeque<I>()
        val onStack = HashSet<I>()
        stack.addLast(start)
        onStack += start
        while (stack.isNotEmpty()) {
            val node = stack.last()
            operationCount++
            val parents = parentsOf(node).orEmpty()
            val pending = parents.firstOrNull { !isVisible(it) && it !in memo && onStack.add(it) }
            if (pending != null) {
                stack.addLast(pending)
                continue
            }
            val found = LinkedHashSet<I>()
            for (parent in parents) {
                operationCount++
                if (isVisible(parent)) found += parent else memo[parent]?.let(found::addAll)
            }
            memo[node] = found
            stack.removeLast()
            onStack -= node
        }
        return memo.getValue(start)
    }
}
