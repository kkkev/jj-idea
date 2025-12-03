package `in`.kkkev.jjidea.log

import com.intellij.vcs.log.TimedVcsCommit

/**
 * A timed commit for Jujutsu (just change ID, parents, and timestamp)
 */
class JujutsuTimedCommit(
    private val changeId: ChangeId,
    private val parentIds: List<ChangeId>,
    private val timestamp: Long
) : TimedVcsCommit {

    override fun getId() = changeId.hashImpl

    override fun getParents() = parentIds.map { it.hashImpl }

    override fun getTimestamp() = timestamp
}
