package `in`.kkkev.jjidea.vcs

import com.intellij.vcs.log.TimedVcsCommit
import `in`.kkkev.jjidea.jj.CommitId
import kotlinx.datetime.Instant

/**
 * A timed commit for Jujutsu (just change ID, parents, and timestamp)
 */
class JujutsuTimedCommit(
    private val commitId: CommitId,
    private val parentIds: List<CommitId>,
    private val timestamp: Instant
) : TimedVcsCommit {
    override fun getId() = commitId.hash

    override fun getParents() = parentIds.map { it.hash }

    override fun getTimestamp() = timestamp.toEpochMilliseconds()

    // Required for IntelliJ's cache duplicate detection
    override fun equals(other: Any?) = when {
        this === other -> true
        other !is JujutsuTimedCommit -> false
        else -> commitId == other.commitId
    }

    override fun hashCode() = id.hashCode()
}
