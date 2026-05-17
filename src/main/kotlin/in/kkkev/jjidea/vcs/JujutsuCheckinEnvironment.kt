package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinEnvironment
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle

/**
 * Checkin environment that rejects commits with a clear explanation.
 * Jujutsu auto-snapshots the working copy — there is no manual commit step.
 * Returning this (non-null) causes the commit dialog to show our message
 * instead of silently discarding the user's commit message.
 */
class JujutsuCheckinEnvironment : CheckinEnvironment {
    override fun getHelpId(): String? = null

    override fun getCheckinOperationName(): String = JujutsuBundle.message("vcs.checkin.operation.name")

    override fun commit(
        changes: List<Change>,
        commitMessage: String,
        commitContext: CommitContext,
        feedback: MutableSet<in String>
    ): List<VcsException> = listOf(VcsException(JujutsuBundle.message("vcs.checkin.not.supported")))

    override fun scheduleMissingFileForDeletion(files: List<FilePath>): List<VcsException>? = null

    override fun scheduleUnversionedFilesForAddition(files: List<VirtualFile>): List<VcsException>? = null

    override fun isRefreshAfterCommitNeeded(): Boolean = false
}
