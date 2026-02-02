package `in`.kkkev.jjidea.vcs

import com.intellij.vcs.log.TimedVcsCommit
import `in`.kkkev.jjidea.jj.ChangeId

/**
 * A timed commit for Jujutsu (just change ID, parents, and timestamp)
 */
class JujutsuTimedCommit(
    private val changeId: ChangeId,
    private val parentIds: List<ChangeId>,
    private val timestamp: Long
) : TimedVcsCommit {
    override fun getId() = changeId.hash

    override fun getParents() = parentIds.map { it.hash }

    override fun getTimestamp() = timestamp

    // Required for IntelliJ's cache duplicate detection
    override fun equals(other: Any?) = when {
        this === other -> true
        other !is JujutsuTimedCommit -> false
        else -> changeId == other.changeId
    }

    override fun hashCode() = id.hashCode()
}
