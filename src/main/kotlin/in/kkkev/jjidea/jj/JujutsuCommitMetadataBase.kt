package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.VcsCommitMetadata

/**
 * Abstract base for Jujutsu commit metadata implementations.
 * Provides common implementation of VcsCommitMetadata methods based on JujutsuLogEntry.
 */
abstract class JujutsuCommitMetadataBase(
    protected val entry: LogEntry,
    private val root: VirtualFile
) : VcsCommitMetadata {
    override fun getId() = entry.changeId.hash
    override fun getParents() = entry.parentIds.map { it.hash }
    override fun getCommitTime() = entry.committerTimestamp
    override fun getTimestamp() = commitTime
    override fun getAuthor() = entry.author
    override fun getFullMessage() = entry.description
    override fun getSubject() = entry.description.lines().firstOrNull() ?: entry.description
    override fun getCommitter() = entry.committer
    override fun getAuthorTime() = entry.authorTimestamp
    override fun getRoot() = root
}
