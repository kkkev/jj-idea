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
    val repo: JujutsuRepository,
    override val id: ChangeId,
    val commitId: CommitId,
    private val underlyingDescription: String,
    val bookmarks: List<Bookmark> = emptyList(),
    val parentIdentifiers: List<Identifiers> = emptyList(),
    override val isWorkingCopy: Boolean = false,
    override val hasConflict: Boolean = false,
    override val isEmpty: Boolean = false,
    val authorTimestamp: Instant? = null,
    val committerTimestamp: Instant? = null,
    val author: VcsUser? = null,
    val committer: VcsUser? = null,
    override val immutable: Boolean = false
) : GraphableEntry, ChangeStatus {
    val description = Description(underlyingDescription)

    override val parentIds: List<ChangeId> get() = parentIdentifiers.map { it.changeId }

    /**
     * Projection of LogEntry that excludes volatile fields (timestamps) from equality.
     * Used by repositoryStates to avoid spurious invalidations when only timestamps change.
     */
    data class StateKey(
        val repo: JujutsuRepository,
        val id: ChangeId,
        val commitId: CommitId,
        val description: String,
        val bookmarks: List<Bookmark>,
        val isWorkingCopy: Boolean,
        val hasConflict: Boolean,
        val isEmpty: Boolean,
        val immutable: Boolean
    )

    val stateKey
        get() = StateKey(
            repo = repo,
            id = id,
            commitId = commitId,
            description = underlyingDescription,
            bookmarks = bookmarks,
            isWorkingCopy = isWorkingCopy,
            hasConflict = hasConflict,
            isEmpty = isEmpty,
            immutable = immutable
        )

    data class Identifiers(val changeId: ChangeId, val commitId: CommitId)
}
