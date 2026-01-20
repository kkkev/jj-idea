package `in`.kkkev.jjidea.jj

import kotlinx.datetime.Instant

/**
 * Minimal commit information for building graphs
 */
data class CommitGraphNode(
    val changeId: ChangeId,
    val parentIds: List<ChangeId>,
    val timestamp: Instant
)
