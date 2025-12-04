package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.VcsCommitMetadata
import com.intellij.vcs.log.VcsUser
import com.intellij.vcs.log.impl.VcsUserImpl

/**
 * Abstract base for Jujutsu commit metadata implementations.
 * Provides common implementation of VcsCommitMetadata methods based on JujutsuLogEntry.
 */
abstract class JujutsuCommitMetadataBase(
    protected val entry: JujutsuLogEntry,
    private val root: VirtualFile
) : VcsCommitMetadata {

    override fun getId(): Hash = entry.changeId.hash

    override fun getParents(): List<Hash> = entry.parentIds.map { it.hash }

    override fun getCommitTime(): Long = entry.committerTimestamp

    override fun getTimestamp(): Long = commitTime

    override fun getAuthor(): VcsUser = VcsUserImpl(entry.authorName, entry.authorEmail)

    override fun getFullMessage(): String = entry.description

    override fun getSubject(): String {
        // First line of description
        val lines = entry.description.lines()
        return if (lines.isNotEmpty()) lines[0] else entry.description
    }

    override fun getCommitter(): VcsUser = VcsUserImpl(entry.committerName, entry.committerEmail)

    override fun getAuthorTime(): Long = entry.authorTimestamp

    override fun getRoot(): VirtualFile = root
}
