package `in`.kkkev.jjidea.vcs.checkin

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.checkin.CheckinEnvironment
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.vcs.JujutsuVcs

/**
 * Checkin environment for Jujutsu VCS
 * Currently read-only (for MVP)
 */
class JujutsuCheckinEnvironment(
    private val vcs: JujutsuVcs
) : CheckinEnvironment {
    override fun getCheckinOperationName(): String = "Commit"

    override fun getHelpId(): String? = null

    override fun getDefaultMessageFor(filesToCheckin: Array<out FilePath>): String? {
        // No default message
        return null
    }

    override fun commit(
        changes: List<Change>,
        preparedComment: String
    ): List<VcsException>? {
        // TODO: Implement actual commit using jj describe + jj commit
        return listOf(VcsException("Commit operation not yet implemented"))
    }

    override fun scheduleMissingFileForDeletion(files: List<FilePath>): List<VcsException>? {
        // Not supported yet
        return null
    }

    override fun scheduleUnversionedFilesForAddition(files: List<VirtualFile>): List<VcsException>? {
        // Not supported yet
        return null
    }

    override fun isRefreshAfterCommitNeeded(): Boolean = true
}
