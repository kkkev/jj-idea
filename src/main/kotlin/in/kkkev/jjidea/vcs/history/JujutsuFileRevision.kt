package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.RepositoryLocation
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.history.VcsFileRevisionEx
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import `in`.kkkev.jjidea.jj.FileChangeStatus
import `in`.kkkev.jjidea.jj.GitRemote
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.annotate.toJavaDate
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber
import java.util.Date

/**
 * Represents a single revision of a file in Jujutsu history
 */
class JujutsuFileRevision(
    val entry: LogEntry,
    private val filePath: FilePath,
    val fileStatus: FileChangeStatus,
    val possibleRemotes: List<GitRemote>
) : VcsFileRevisionEx() {
    val commitId get() = entry.commitId
    val immutable get() = entry.immutable
    val repoRelativePath get() = entry.repo.getRelativePath(filePath)
    val committer get() = entry.committer?.name
    val committerDate get() = entry.committerTimestamp?.toJavaDate()

    override fun getRevisionNumber(): VcsRevisionNumber = JujutsuRevisionNumber(entry.id)

    override fun getBranchName(): String = entry.bookmarks.firstOrNull()?.name ?: ""

    override fun getRevisionDate() = entry.authorTimestamp?.toJavaDate()

    override fun getAuthor() = entry.author?.name

    override fun getAuthorEmail(): String? = entry.author?.email

    override fun getAuthorDate(): Date? = entry.authorTimestamp?.let { Date(it.toEpochMilliseconds()) }

    override fun getCommitterName(): String? = entry.committer?.name

    override fun getCommitterEmail(): String? = entry.committer?.email

    override fun getCommitMessage(): String = entry.description.display

    // TODO Fill this in
    override fun getChangedRepositoryPath(): RepositoryLocation? = null

    override fun getPath(): FilePath = filePath

    override fun isDeleted(): Boolean = fileStatus == FileChangeStatus.DELETED

    @Throws(VcsException::class)
    override fun loadContent(): ByteArray {
        val result = entry.repo.commandExecutor.show(filePath, entry.id)
        if (!result.isSuccess) {
            throw VcsException("Failed to load file content at revision ${entry.id}: ${result.stderr}")
        }
        return result.stdout.toByteArray()
    }

    @Throws(VcsException::class)
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getContent(): ByteArray = loadContent()
}
