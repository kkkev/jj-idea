package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsHistorySession
import com.intellij.openapi.vcs.history.VcsRevisionNumber

/**
 * Represents a history session for a file, holding all revisions
 */
class JujutsuHistorySession(
    private val revisions: List<VcsFileRevision>,
    private val filePath: FilePath
) : VcsHistorySession {

    override fun getRevisionList() = revisions

    override fun getCurrentRevisionNumber(): VcsRevisionNumber? = revisions.firstOrNull()?.revisionNumber

    override fun isCurrentRevision(revisionNumber: VcsRevisionNumber) =
        revisionNumber == getCurrentRevisionNumber()

    override fun shouldBeRefreshed() = false

    override fun isContentAvailable(revision: VcsFileRevision) = true

    override fun hasLocalSource() = false
}
