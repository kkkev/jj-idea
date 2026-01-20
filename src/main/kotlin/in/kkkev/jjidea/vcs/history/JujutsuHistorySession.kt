package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.vcs.history.VcsAbstractHistorySession
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber

/**
 * Represents a history session for a file, holding all revisions
 */
class JujutsuHistorySession(
    revisions: List<VcsFileRevision>,
    currentRevisionNumber: VcsRevisionNumber?
) : VcsAbstractHistorySession(revisions, currentRevisionNumber) {
    override fun calcCurrentRevisionNumber(): VcsRevisionNumber? = null

    override fun copy() = JujutsuHistorySession(revisionList, currentRevisionNumber)

    override fun isContentAvailable(revision: VcsFileRevision) = true

    override fun hasLocalSource() = false
}
