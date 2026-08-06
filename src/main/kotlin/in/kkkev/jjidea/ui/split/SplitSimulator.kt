package `in`.kkkev.jjidea.ui.split

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.LogEntry

/**
 * Result of a simulated split — the entries and metadata needed to render the preview graph.
 */
data class SplitSimulation(
    val entries: List<LogEntry>,
    val parentId: ChangeId,
    val childId: ChangeId
)

/**
 * Pure-logic simulator for `jj split` operations.
 *
 * Given a source entry and its context (parents and children), produces a [SplitSimulation]
 * containing synthetic entries for the split result, suitable for graph rendering in the preview panel.
 *
 * Linear mode: source → parent (keeps position) + child (new, child of parent)
 * Parallel mode: source → first + second (siblings, both children of source's parents)
 */
object SplitSimulator {
    private val SYNTHETIC_COMMIT = CommitId("0000000000000000000000000000000000000000")

    fun simulate(
        allEntries: List<LogEntry>,
        sourceEntry: LogEntry,
        parallel: Boolean,
        parentDescription: String? = null,
        childDescription: String? = null
    ): SplitSimulation {
        val parentId = ChangeId("${sourceEntry.id.short}'", "${sourceEntry.id.short}'")
        val childId = ChangeId("${sourceEntry.id.short}''", "${sourceEntry.id.short}''")

        val sourceParentIds = sourceEntry.parentIdentifiers

        val childIdentifiers: List<LogEntry.Identifiers>
        val parentEntry: LogEntry
        val childEntry: LogEntry

        if (parallel) {
            // Parallel: both are children of source's parents (siblings)
            childIdentifiers = sourceParentIds

            parentEntry = sourceEntry.copy(
                id = parentId,
                commitId = SYNTHETIC_COMMIT,
                underlyingDescription = parentDescription ?: sourceEntry.description.actual,
                parentIdentifiers = sourceParentIds,
                isWorkingCopy = false
            )
            childEntry = sourceEntry.copy(
                id = childId,
                commitId = SYNTHETIC_COMMIT,
                underlyingDescription = childDescription ?: sourceEntry.description.actual,
                parentIdentifiers = childIdentifiers,
                isWorkingCopy = false
            )
        } else {
            // Linear: parent keeps original position, child is child of parent
            childIdentifiers = listOf(LogEntry.Identifiers(parentId, SYNTHETIC_COMMIT))

            parentEntry = sourceEntry.copy(
                id = parentId,
                commitId = SYNTHETIC_COMMIT,
                underlyingDescription = parentDescription ?: sourceEntry.description.actual,
                parentIdentifiers = sourceParentIds,
                isWorkingCopy = false
            )
            childEntry = sourceEntry.copy(
                id = childId,
                commitId = SYNTHETIC_COMMIT,
                underlyingDescription = childDescription ?: sourceEntry.description.actual,
                parentIdentifiers = childIdentifiers,
                isWorkingCopy = sourceEntry.isWorkingCopy
            )
        }

        // Reparent children of source to point to child (linear) or both (parallel)
        val sourceId = sourceEntry.id
        val reparentedEntries = allEntries.mapNotNull { entry ->
            if (entry.id == sourceId) return@mapNotNull null // Remove source

            val hasSourceAsParent = entry.parentIdentifiers.any { it.changeId == sourceId }
            if (!hasSourceAsParent) return@mapNotNull entry

            if (parallel) {
                // Children of source become children of both split results (merge)
                val newParents = entry.parentIdentifiers.flatMap { pid ->
                    if (pid.changeId == sourceId) {
                        listOf(
                            LogEntry.Identifiers(parentId, SYNTHETIC_COMMIT),
                            LogEntry.Identifiers(childId, SYNTHETIC_COMMIT)
                        )
                    } else {
                        listOf(pid)
                    }
                }
                entry.copy(parentIdentifiers = newParents)
            } else {
                // Children of source become children of child
                val newParents = entry.parentIdentifiers.map { pid ->
                    if (pid.changeId == sourceId) {
                        LogEntry.Identifiers(childId, SYNTHETIC_COMMIT)
                    } else {
                        pid
                    }
                }
                entry.copy(parentIdentifiers = newParents)
            }
        }

        // Build the result: context entries + split results, scoped and sorted
        val allResultEntries = reparentedEntries + parentEntry + childEntry
        val scoped = scopeToRelevant(allResultEntries, sourceEntry, parentId, childId)
        val sorted = topologicalSort(scoped)

        return SplitSimulation(
            entries = sorted,
            parentId = parentId,
            childId = childId
        )
    }

    /**
     * Scope to entries relevant for the preview: parents/children of the split + the split results.
     * Shows approximately 5 entries for context.
     */
    private fun scopeToRelevant(
        allEntries: List<LogEntry>,
        sourceEntry: LogEntry,
        parentId: ChangeId,
        childId: ChangeId
    ): List<LogEntry> {
        val interestingIds = mutableSetOf(parentId, childId)

        // Add source's parents (1 level)
        interestingIds.addAll(sourceEntry.parentIds)

        // Add source's children (entries that had source as parent, now reparented)
        val childrenOfSource = allEntries.filter { entry ->
            entry.parentIds.any { it == parentId || it == childId }
        }.map { it.id }
        interestingIds.addAll(childrenOfSource)

        // Add grandparents for a bit more context
        val entryById = allEntries.associateBy { it.id }
        sourceEntry.parentIds.forEach { pid ->
            entryById[pid]?.parentIds?.let { interestingIds.addAll(it) }
        }

        return allEntries.filter { it.id in interestingIds }
    }

    /**
     * Topological sort: children before parents (newest first), matching jj log order.
     */
    private fun topologicalSort(entries: List<LogEntry>): List<LogEntry> {
        if (entries.size <= 1) return entries

        val entryById = entries.associateBy { it.id }
        val entryIds = entryById.keys

        // Build children map (only within this set)
        val childrenOf = mutableMapOf<ChangeId, MutableSet<ChangeId>>()
        for (entry in entries) {
            for (parentId in entry.parentIds) {
                if (parentId in entryIds) {
                    childrenOf.getOrPut(parentId) { mutableSetOf() }.add(entry.id)
                }
            }
        }

        // In-degree = number of children pointing to this entry
        val inDegree = mutableMapOf<ChangeId, Int>()
        for (id in entryIds) {
            inDegree[id] = childrenOf[id]?.size ?: 0
        }

        // Start with entries that have no children (in-degree 0)
        val originalIndex = entries.withIndex().associate { (i, e) -> e.id to i }
        val queue = java.util.PriorityQueue<ChangeId>(compareBy { originalIndex[it] ?: Int.MAX_VALUE })
        for ((id, deg) in inDegree) {
            if (deg == 0) queue.add(id)
        }

        val result = mutableListOf<LogEntry>()
        while (queue.isNotEmpty()) {
            val id = queue.poll()
            val entry = entryById[id] ?: continue
            result.add(entry)

            for (parentId in entry.parentIds) {
                if (parentId !in entryIds) continue
                val newDeg = (inDegree[parentId] ?: 1) - 1
                inDegree[parentId] = newDeg
                if (newDeg == 0) queue.add(parentId)
            }
        }

        // Append leftovers (shouldn't happen with valid data)
        if (result.size < entries.size) {
            val resultIds = result.map { it.id }.toSet()
            entries.filter { it.id !in resultIds }.forEach { result.add(it) }
        }

        return result
    }
}
