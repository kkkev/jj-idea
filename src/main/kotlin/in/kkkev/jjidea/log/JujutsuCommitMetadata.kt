package `in`.kkkev.jjidea.log

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.VcsCommitMetadata
import com.intellij.vcs.log.VcsUser
import com.intellij.vcs.log.impl.VcsUserImpl
import `in`.kkkev.jjidea.ui.JujutsuLogEntry

/**
 * Metadata for a Jujutsu commit
 */
class JujutsuCommitMetadata(private val entry: JujutsuLogEntry, private val root: VirtualFile) : VcsCommitMetadata {

    override fun getId() = entry.changeId.hashImpl

    override fun getParents(): List<Hash> = entry.parentIds.map { it.hashImpl }

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
