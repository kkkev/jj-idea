package `in`.kkkev.jjidea.ui.squash

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.rebase.RebaseSimulator

/**
 * Result of a simulated squash - the entries and metadata needed to render the preview graph.
 *
 * [sourceIds] are the sources still present in [entries] (empty-or-remainder, i.e. kept);
 * [abandonedIds] are the sources removed from it.
 */
data class SquashSimulation(
    val entries: List<LogEntry>,
    val sourceIds: Set<ChangeId>,
    val destinationId: ChangeId?,
    val abandonedIds: Set<ChangeId>
)

/**
 * Pure-logic simulator for `jj squash --from S... --into D`.
 *
 * Each source's diff moves into the destination. A source is abandoned when [simulate]'s
 * `abandonSources` is set (the dialog's "delete empty source" option, only ever in effect for a
 * whole-change squash); otherwise it stays in place, emptied or holding a remainder. An abandoned
 * source's children are re-parented onto its parents. Abandoning the working copy makes the
 * destination the new working copy, matching `executeSquashInto`'s follow-up `jj edit`.
 *
 * Reuses [RebaseSimulator]'s generic DAG helpers; every step is linear apart from the one
 * topological sort.
 */
object SquashSimulator {
    fun simulate(
        allEntries: List<LogEntry>,
        sources: List<LogEntry>,
        destination: LogEntry?,
        abandonSources: Boolean
    ): SquashSimulation {
        if (sources.isEmpty() || destination == null) {
            return SquashSimulation(allEntries, emptySet(), destination?.id, emptySet())
        }

        // A source can't also be its own destination; ignore it rather than abandon the target.
        val sourceIds = sources.map { it.id }.filter { it != destination.id }.toSet()
        val abandoned = if (abandonSources) sourceIds else emptySet()
        val kept = sourceIds - abandoned
        val workingCopyAbandoned = sources.any { it.isWorkingCopy && it.id in abandoned }

        var entries = RebaseSimulator.removeNodes(allEntries, abandoned)
        if (workingCopyAbandoned) {
            entries = entries.map { if (it.id == destination.id) it.copy(isWorkingCopy = true) else it }
        }

        val scoped = RebaseSimulator.scopeToRelevant(entries, kept, setOf(destination.id))
        return SquashSimulation(
            entries = RebaseSimulator.topologicalSort(scoped),
            sourceIds = kept,
            destinationId = destination.id,
            abandonedIds = abandoned
        )
    }
}
