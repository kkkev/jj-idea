package `in`.kkkev.jjidea.jj

import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.ui.log.DagNode
import `in`.kkkev.jjidea.ui.log.GraphableEntry
import kotlinx.datetime.Instant

/**
 * Represents a single entry in the jj log.
 * This is a pure data class / DTO representing parsed JJ log output.
 */
data class LogEntry(
    override val repo: JujutsuRepository,
    override val id: ChangeId,
    override val commitId: CommitId,
    private val underlyingDescription: String,
    val bookmarks: List<Bookmark> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val parentIdentifiers: List<Identifiers> = emptyList(),
    override val isWorkingCopy: Boolean = false,
    override val hasConflict: Boolean = false,
    override val isEmpty: Boolean = false,
    override val authorTimestamp: Instant? = null,
    val committerTimestamp: Instant? = null,
    override val author: VcsUser? = null,
    val committer: VcsUser? = null,
    override val immutable: Boolean = false,
    val hasPushedAncestor: Boolean = false,
    /**
     * True for a not-yet-created change previewed in a rebase-simulator preview (e.g.
     * [in.kkkev.jjidea.ui.newchange.NewChangeDialog]'s "the change about to be created" row).
     * [id]/[commitId] are placeholders with no real backing commit - the log-row renderer checks
     * this flag to paint a deliberately minimal representation instead of attempting real-commit
     * rendering (bookmarks/tags/status badges/a `jjc://` navigation link), which a pending entry
     * structurally can't support.
     */
    val pending: Boolean = false
) : GraphableEntry, ChangeStatus, ChangeDetail, DagNode<LogEntry> {
    override val description = Description(underlyingDescription)

    override val parentIds: List<ChangeId> get() = parentIdentifiers.map { it.changeId }

    /**
     * See [DagNode.withParents] - used by [in.kkkev.jjidea.ui.rebase.RebaseSimulator] to reparent
     * a simulated entry. The simulator only ever deals in [ChangeId]s, so the resulting
     * [Identifiers] get a [CommitId.PLACEHOLDER] - never a real commit id.
     */
    override fun withParents(parentIds: List<ChangeId>): LogEntry =
        copy(parentIdentifiers = parentIds.map { Identifiers(it, CommitId.PLACEHOLDER) })

    val isDivergent get() = id.divergent

    /**
     * Returns the content locator to use as "before" content for a log entry's parent.
     * For merge commits (multiple parents), returns [MergeParentOf] so that content is
     * reconstructed via reverse-apply of the entry's diff rather than using first-parent content.
     */
    val parentContentLocator
        get() = when (parentIds.size) {
            1 -> parentIds.first()
            0 -> ContentLocator.Empty
            else -> MergeParentOf(id)
        }

    /**
     * Projection of LogEntry that excludes volatile fields (timestamps) from equality.
     * Used by workingCopies to avoid spurious invalidations when only timestamps change.
     */
    data class StateKey(
        val repo: JujutsuRepository,
        val id: ChangeId,
        val commitId: CommitId,
        val description: String,
        val bookmarks: List<Bookmark>,
        val tags: List<Tag>,
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
            tags = tags,
            isWorkingCopy = isWorkingCopy,
            hasConflict = hasConflict,
            isEmpty = isEmpty,
            immutable = immutable
        )

    data class Identifiers(val changeId: ChangeId, val commitId: CommitId)
}
