package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.RepositoryLocation
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.annotate.toJavaDate

/**
 * Represents a single revision of a file in Jujutsu history
 */
class JujutsuFileRevision(
    private val entry: LogEntry,
    private val filePath: FilePath,
    private val vcs: JujutsuVcs
) : VcsFileRevision {

    override fun getRevisionNumber(): VcsRevisionNumber = JujutsuRevisionNumber(entry.changeId)

    override fun getBranchName(): String = entry.bookmarks.firstOrNull()?.name ?: ""

    override fun getRevisionDate() = entry.authorTimestamp?.toJavaDate()

    override fun getAuthor() = entry.author?.name

    override fun getCommitMessage(): String = entry.description.ifEmpty { "(no description)" }

    // TODO Fill this in
    override fun getChangedRepositoryPath(): RepositoryLocation? = null

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
    override fun getContent(): ByteArray = loadContent()

    /**
     * Get the full log entry for this revision
     */
    fun getLogEntry() = entry
}
