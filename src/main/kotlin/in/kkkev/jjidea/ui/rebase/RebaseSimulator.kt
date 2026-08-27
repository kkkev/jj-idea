package `in`.kkkev.jjidea.ui.rebase

import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.log.DagNode
import java.util.*

/**
 * Result of a simulated rebase — the entries and metadata needed to render the preview graph.
 */
data class RebaseSimulation(
    val entries: List<LogEntry>,
    val sourceIds: Set<ChangeId>,
    val destinationIds: Set<ChangeId>,
    val reparentedIds: Set<ChangeId>
)

/**
 * Pure-logic simulator for `jj rebase` operations.
 *
 * Given a set of log entries and rebase parameters, produces a [RebaseSimulation]
 * containing reparented entries suitable for graph rendering in the preview panel.
 *
 * The actual graph-shuffling (everything below [simulate]) only ever needs [ChangeId]-level
 * identity and parent linkage, so it's written generically against [DagNode] rather than
 * concrete [LogEntry] - [simulate] itself stays concrete (its public signature is unchanged)
 * and is just the `T = LogEntry` instantiation of that generic core, the same way
 * [in.kkkev.jjidea.ui.log.CommitGraphBuilder] is a concrete wrapper around the fully generic
 * [in.kkkev.jjidea.ui.log.graph.LayoutCalculatorImpl].
 */
object RebaseSimulator {
    fun simulate(
        allEntries: List<LogEntry>,
        sourceEntries: List<LogEntry>,
        destinationIds: Set<ChangeId>,
        sourceMode: RebaseSourceMode,
        destinationMode: RebaseDestinationMode
    ): RebaseSimulation {
        if (sourceEntries.isEmpty() || destinationIds.isEmpty()) {
            return RebaseSimulation(allEntries, emptySet(), destinationIds, emptySet())
        }

        val entryById = allEntries.associateBy { it.id }
        val sourceIds = sourceEntries.map { it.id }.toSet()

        // 1. Collect moved entries based on source mode
        val movedIds = collectMovedIds(allEntries, sourceIds, sourceMode)

        // 2. Reparent moved entries based on destination mode
        val reparented = reparent(allEntries, entryById, movedIds, destinationIds, destinationMode)

        // 3. Scope to relevant entries
        val scoped = scopeToRelevant(reparented, movedIds, destinationIds)

        // 4. Topologically re-sort
        val sorted = topologicalSort(scoped)

        val reparentedIds = sorted.filter { it.id in movedIds }.map { it.id }.toSet()

        return RebaseSimulation(
            entries = sorted,
            sourceIds = movedIds,
            destinationIds = destinationIds,
            reparentedIds = reparentedIds
        )
    }

    /**
     * Collect the set of change IDs that will be moved, based on source mode.
     */
    internal fun <T : DagNode<T>> collectMovedIds(
        allEntries: List<T>,
        sourceIds: Set<ChangeId>,
        sourceMode: RebaseSourceMode
    ): Set<ChangeId> = when (sourceMode) {
        RebaseSourceMode.REVISION -> sourceIds
        RebaseSourceMode.SOURCE -> sourceIds + collectDescendants(allEntries, sourceIds)
        RebaseSourceMode.BRANCH -> collectBranch(allEntries, sourceIds)
    }

    /**
     * Compute the set of change IDs that must NOT be selected as destinations for a given source mode.
     *
     * - **REVISION**: only the source entries themselves (jj allows `-r` onto descendants)
     * - **SOURCE**: sources + all their descendants (cycle)
     * - **BRANCH**: entire branch containing the sources (cycle)
     */
    fun excludedDestinationIds(
        allEntries: List<LogEntry>,
        sourceIds: Set<ChangeId>,
        sourceMode: RebaseSourceMode
    ): Set<ChangeId> = collectMovedIds(allEntries, sourceIds, sourceMode)

    /**
     * Collect all descendants of the given roots (children, grandchildren, etc.).
     */
    internal fun <T : DagNode<T>> collectDescendants(entries: List<T>, roots: Set<ChangeId>): Set<ChangeId> {
        val childrenOf = buildChildrenMap(entries)
        val result = mutableSetOf<ChangeId>()
        val queue: Queue<ChangeId> = LinkedList(roots)
        while (queue.isNotEmpty()) {
            val current = queue.poll()
            childrenOf[current]?.forEach { child ->
                if (result.add(child)) queue.add(child)
            }
        }
        return result
    }

    /**
     * Collect the entire branch containing the source entries:
     * walk up to roots (entries with no parents in the set), then collect all descendants.
     */
    internal fun <T : DagNode<T>> collectBranch(entries: List<T>, sourceIds: Set<ChangeId>): Set<ChangeId> {
        val entryById = entries.associateBy { it.id }
        val allIds = entries.map { it.id }.toSet()

        // Walk up from sources to find branch roots (entries whose parents are not in the graph or are immutable)
        val branchRoots = mutableSetOf<ChangeId>()
        val visited = mutableSetOf<ChangeId>()
        val queue: Queue<ChangeId> = LinkedList(sourceIds)
        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (!visited.add(current)) continue
            val entry = entryById[current]
            val parentIdsInGraph = entry?.parentIds?.filter { it in allIds } ?: emptyList()
            if (parentIdsInGraph.isEmpty()) {
                branchRoots.add(current)
            } else {
                parentIdsInGraph.forEach { queue.add(it) }
            }
        }

        // Now collect all descendants of the branch roots
        return branchRoots + collectDescendants(entries, branchRoots)
    }

    /**
     * Reparent moved entries based on destination mode.
     * Returns the full list of entries with moved entries reparented.
     */
    internal fun <T : DagNode<T>> reparent(
        allEntries: List<T>,
        entryById: Map<ChangeId, T>,
        movedIds: Set<ChangeId>,
        destinationIds: Set<ChangeId>,
        destinationMode: RebaseDestinationMode
    ): List<T> {
        // Find the roots and tips of the moved set
        val movedRoots = movedIds.filter { id ->
            val entry = entryById[id] ?: return@filter true
            entry.parentIds.none { it in movedIds }
        }.toSet()
        val movedTips = movedIds.filter { id ->
            val childrenOf = buildChildrenMap(allEntries)
            val children = childrenOf[id] ?: emptySet()
            children.none { it in movedIds }
        }.toSet()

        return when (destinationMode) {
            RebaseDestinationMode.ONTO -> reparentOnto(allEntries, movedIds, movedRoots, destinationIds)
            RebaseDestinationMode.INSERT_AFTER -> reparentInsertAfter(
                allEntries,
                movedIds,
                movedRoots,
                movedTips,
                destinationIds
            )
            RebaseDestinationMode.INSERT_BEFORE -> reparentInsertBefore(
                allEntries,
                entryById,
                movedIds,
                movedRoots,
                movedTips,
                destinationIds
            )
        }
    }

    /**
     * ONTO: Each moved root's parents become the destination IDs.
     * Internal parent links within the moved set stay the same.
     */
    internal fun <T : DagNode<T>> reparentOnto(
        allEntries: List<T>,
        movedIds: Set<ChangeId>,
        movedRoots: Set<ChangeId>,
        destinationIds: Set<ChangeId>
    ): List<T> {
        val destinationList = destinationIds.toList()

        return allEntries.map { entry ->
            when {
                entry.id in movedRoots -> entry.withParents(destinationList)
                entry.id in movedIds -> entry // Keep internal links
                else -> {
                    // Non-moved entries: if they had moved entries as parents, repoint to moved entries' original parents
                    val newParents = entry.parentIds.flatMap { parentId ->
                        if (parentId in movedIds) {
                            // Skip this parent — the moved entry is no longer here
                            // For REVISION mode, descendants of moved entries need new parents
                            val movedEntry = allEntries.find { it.id == parentId }
                            movedEntry?.parentIds ?: listOf(parentId)
                        } else {
                            listOf(parentId)
                        }
                    }
                    if (newParents != entry.parentIds) entry.withParents(newParents) else entry
                }
            }
        }
    }

    /**
     * INSERT_AFTER: Moved entries become children of destinations.
     * Destinations' former children become children of moved tips.
     */
    internal fun <T : DagNode<T>> reparentInsertAfter(
        allEntries: List<T>,
        movedIds: Set<ChangeId>,
        movedRoots: Set<ChangeId>,
        movedTips: Set<ChangeId>,
        destinationIds: Set<ChangeId>
    ): List<T> {
        val destinationList = destinationIds.toList()
        val tipList = movedTips.toList()

        return allEntries.map { entry ->
            when {
                // Moved roots: parents become destinations
                entry.id in movedRoots -> entry.withParents(destinationList)
                // Other moved entries: keep internal links
                entry.id in movedIds -> entry
                // Non-moved entries that were children of destinations: reparent to moved tips
                else -> {
                    val hasDestParent = entry.parentIds.any { it in destinationIds }
                    if (hasDestParent && entry.id !in movedIds) {
                        val newParents = entry.parentIds.flatMap { parentId ->
                            if (parentId in destinationIds) tipList else listOf(parentId)
                        }
                        entry.withParents(newParents)
                    } else {
                        entry
                    }
                }
            }
        }
    }

    /**
     * INSERT_BEFORE: Moved entries become parents of destinations.
     * Destinations' former parents become parents of moved roots.
     */
    internal fun <T : DagNode<T>> reparentInsertBefore(
        allEntries: List<T>,
        entryById: Map<ChangeId, T>,
        movedIds: Set<ChangeId>,
        movedRoots: Set<ChangeId>,
        movedTips: Set<ChangeId>,
        destinationIds: Set<ChangeId>
    ): List<T> {
        // Destinations' original parents
        val destOriginalParents = destinationIds.flatMap { destId ->
            entryById[destId]?.parentIds ?: emptyList()
        }.distinct()

        val tipList = movedTips.toList()

        return allEntries.map { entry ->
            when {
                // Moved roots: parents become destinations' original parents
                entry.id in movedRoots -> entry.withParents(destOriginalParents)
                // Other moved entries: keep internal links
                entry.id in movedIds -> entry
                // Destinations: parents become moved tips
                entry.id in destinationIds -> entry.withParents(tipList)
                else -> entry
            }
        }
    }

    /**
     * Scope the entries to only those relevant to the rebase:
     * ancestors and descendants of source + destination entries.
     */
    internal fun <T : DagNode<T>> scopeToRelevant(
        allEntries: List<T>,
        sourceIds: Set<ChangeId>,
        destinationIds: Set<ChangeId>
    ): List<T> {
        if (allEntries.size <= 20) return allEntries

        val interestingIds = sourceIds + destinationIds
        val entryById = allEntries.associateBy { it.id }
        val childrenOf = buildChildrenMap(allEntries)

        // Collect ancestors of interesting entries
        val ancestors = mutableSetOf<ChangeId>()
        val ancestorQueue: Queue<ChangeId> = LinkedList(interestingIds)
        while (ancestorQueue.isNotEmpty()) {
            val current = ancestorQueue.poll()
            if (!ancestors.add(current)) continue
            entryById[current]?.parentIds?.forEach { ancestorQueue.add(it) }
        }

        // Collect descendants of interesting entries
        val descendants = mutableSetOf<ChangeId>()
        val descQueue: Queue<ChangeId> = LinkedList(interestingIds)
        while (descQueue.isNotEmpty()) {
            val current = descQueue.poll()
            if (!descendants.add(current)) continue
            childrenOf[current]?.forEach { descQueue.add(it) }
        }

        val relevant = ancestors + descendants
        val scoped = allEntries.filter { it.id in relevant }

        // If scoping yielded too few entries, fall back to all
        return if (scoped.size < 2) allEntries else scoped
    }

    /**
     * Topological sort: children before parents (newest first), matching jj log order.
     * Uses Kahn's algorithm on in-degree (number of children pointing to each entry).
     */
    internal fun <T : DagNode<T>> topologicalSort(entries: List<T>): List<T> {
        if (entries.size <= 1) return entries

        val entryById = entries.associateBy { it.id }
        val entryIds = entryById.keys
        val originalIndex = entries.withIndex().associate { (i, e) -> e.id to i }

        // Build children map (only within this set of entries)
        val childrenOf = mutableMapOf<ChangeId, MutableSet<ChangeId>>()
        for (entry in entries) {
            for (parentId in entry.parentIds) {
                if (parentId in entryIds) {
                    childrenOf.getOrPut(parentId) { mutableSetOf() }.add(entry.id)
                }
            }
        }

        // In-degree = number of children (entries that list this as parent)
        val inDegree = mutableMapOf<ChangeId, Int>()
        for (id in entryIds) {
            inDegree[id] = childrenOf[id]?.size ?: 0
        }

        // Priority queue: entries with in-degree 0 (no children pointing to them), tie-break by original index
        val queue = PriorityQueue<ChangeId>(compareBy { originalIndex[it] ?: Int.MAX_VALUE })
        for ((id, deg) in inDegree) {
            if (deg == 0) queue.add(id)
        }

        val result = mutableListOf<T>()
        while (queue.isNotEmpty()) {
            val id = queue.poll()
            val entry = entryById[id] ?: continue
            result.add(entry)

            // "Remove" this entry: decrement in-degree of its parents
            for (parentId in entry.parentIds) {
                if (parentId !in entryIds) continue
                val newDeg = (inDegree[parentId] ?: 1) - 1
                inDegree[parentId] = newDeg
                if (newDeg == 0) queue.add(parentId)
            }
        }

        // If cycle or missing entries, append any leftovers
        if (result.size < entries.size) {
            val resultIds = result.map { it.id }.toSet()
            entries.filter { it.id !in resultIds }.forEach { result.add(it) }
        }

        return result
    }

    /**
     * Build a map of parent → set of children.
     */
    private fun <T : DagNode<T>> buildChildrenMap(entries: List<T>): Map<ChangeId, Set<ChangeId>> {
        val childrenOf = mutableMapOf<ChangeId, MutableSet<ChangeId>>()
        for (entry in entries) {
            for (parentId in entry.parentIds) {
                childrenOf.getOrPut(parentId) { mutableSetOf() }.add(entry.id)
            }
        }
        return childrenOf
    }
}
