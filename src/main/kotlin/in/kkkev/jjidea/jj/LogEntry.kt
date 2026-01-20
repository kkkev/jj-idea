package `in`.kkkev.jjidea.jj

import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.ui.log.GraphableEntry
import kotlinx.datetime.Instant

/**
 * Represents a single entry in the jj log.
 * This is a pure data class / DTO representing parsed JJ log output.
 * Conversion to VCS framework objects (VcsUser, VcsCommitMetadata, etc.)
 * is handled by JujutsuCommitMetadataBase and its subclasses.
 */
data class LogEntry(
    override val changeId: ChangeId,
    val commitId: String,
    private val underlyingDescription: String,
    val bookmarks: List<Bookmark> = emptyList(),
    override val parentIds: List<ChangeId> = emptyList(),
    val isWorkingCopy: Boolean = false,
    val hasConflict: Boolean = false,
    val isEmpty: Boolean = false,
    val authorTimestamp: Instant? = null,
    val committerTimestamp: Instant? = null,
    val author: VcsUser? = null,
    val committer: VcsUser? = null,
    val immutable: Boolean = false
) : GraphableEntry {
    val description = Description(underlyingDescription)
}
