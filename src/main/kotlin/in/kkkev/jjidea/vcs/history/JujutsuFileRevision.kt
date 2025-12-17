package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.RepositoryLocation
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.history.VcsFileRevisionEx
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import `in`.kkkev.jjidea.jj.FileChangeStatus
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.annotate.toJavaDate
import java.util.Date

/**
 * Represents a single revision of a file in Jujutsu history
 */
class JujutsuFileRevision(
    private val entry: LogEntry,
    private val filePath: FilePath,
    private val vcs: JujutsuVcs
) : VcsFileRevisionEx() {

    private val log = Logger.getInstance(JujutsuFileRevision::class.java)

    /**
     * Lazy-loaded file status. Determines if this file was deleted in this revision.
     */
    private val fileStatus: FileChangeStatus? by lazy {
        vcs.logService.getFileChanges(entry.changeId).getOrElse { error ->
            log.debug("Error loading file changes for ${entry.changeId}: ${error.message}")
            emptyList()
        }.find { it.filePath == filePath.path }?.status
    }

    override fun getRevisionNumber(): VcsRevisionNumber = JujutsuRevisionNumber(entry.changeId)

    override fun getBranchName(): String = entry.bookmarks.firstOrNull()?.name ?: ""

    override fun getRevisionDate() = entry.authorTimestamp?.toJavaDate()

    override fun getAuthor() = entry.author?.name

    override fun getAuthorEmail(): String? = entry.author?.email

    override fun getAuthorDate(): Date? = entry.authorTimestamp?.let { Date(it.toEpochMilliseconds()) }

    override fun getCommitterName(): String? = entry.committer?.name

    override fun getCommitterEmail(): String? = entry.committer?.email

    override fun getCommitMessage(): String = entry.description.ifEmpty { "(no description)" }

    /**
     * Get the committer name (may differ from author in JJ)
     */
    fun getCommitter() = entry.committer?.name

    /**
     * Get the committer timestamp
     */
    fun getCommitterDate() = entry.committerTimestamp?.toJavaDate()

    // TODO Fill this in
    override fun getChangedRepositoryPath(): RepositoryLocation? = null

    override fun getPath(): FilePath = filePath

    override fun isDeleted(): Boolean = fileStatus == FileChangeStatus.DELETED

    @Throws(VcsException::class)
    override fun loadContent(): ByteArray {
        val relativePath = vcs.getRelativePath(filePath)
        val result = vcs.commandExecutor.show(relativePath, entry.changeId)
        if (!result.isSuccess) {
            throw VcsException("Failed to load file content at revision ${entry.changeId}: ${result.stderr}")
        }
        return result.stdout.toByteArray()
    }

    @Throws(VcsException::class)
    @Deprecated("Use loadContent() instead")
    override fun getContent(): ByteArray = loadContent()

    /**
     * Get the full log entry for this revision
     */
    fun getLogEntry() = entry

    // VcsFileRevisionEx abstract methods
    override fun getAuthorEmail(): String? = entry.author?.email

    override fun getCommitterName(): String? = entry.committer?.name

    override fun getCommitterEmail(): String? = entry.committer?.email

    override fun getPath(): FilePath = filePath

    override fun getAuthorDate(): Date? = entry.authorTimestamp?.toJavaDate()

    override fun isDeleted(): Boolean = false // TODO: Determine from file status
}
