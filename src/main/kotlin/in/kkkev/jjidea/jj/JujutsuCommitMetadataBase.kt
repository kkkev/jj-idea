package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.VcsCommitMetadata

/**
 * Abstract base for Jujutsu commit metadata implementations.
 * Provides common implementation of VcsCommitMetadata methods based on JujutsuLogEntry.
 */
abstract class JujutsuCommitMetadataBase(
    val entry: LogEntry, // Public to allow column rendering access
    private val root: VirtualFile
) : VcsCommitMetadata {
    override fun getId() = entry.changeId.hash

    override fun getParents() = entry.parentIds.map { it.hash }

    // TODO What if null?
    override fun getCommitTime() = entry.committerTimestamp!!.toEpochMilliseconds()

    override fun getTimestamp() = commitTime

    // TODO What if null?
    override fun getAuthor() = entry.author!!

    override fun getFullMessage() = entry.description.display

    override fun getSubject() = entry.description.summary

    override fun getCommitter() = entry.committer!!

    override fun getAuthorTime() = entry.authorTimestamp!!.toEpochMilliseconds()

    override fun getRoot() = root

    // Required for IntelliJ's TopCommitsCache.containsValue() duplicate detection
    override fun equals(other: Any?) = when {
        this === other -> true
        other !is JujutsuCommitMetadataBase -> false
        else -> entry.changeId == other.entry.changeId && root == other.root
    }

    override fun hashCode() = 31 * id.hashCode() + root.hashCode()
}
