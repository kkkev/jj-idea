package `in`.kkkev.jjidea.jj

/**
 * Common interface for named repository references (bookmarks, tags) retrieved from listing commands.
 * Both [BookmarkItem] and [TagItem] are [RefItem]s, distinguishable by the [ref] type.
 */
sealed interface RefItem {
    /** The reference name, usable as a [Revision] in jj commands. */
    val ref: Ref

    /**
     * Every change this ref currently targets, in the order jj's `added_targets` reports them -
     * more than one exactly when the ref is conflicted/divergent (jj-idea-bico). Empty for a
     * deleted/pending-delete ref.
     */
    val targets: List<ChangeId>

    /** Per-target immutability, positionally aligned with [targets]. */
    val immutables: List<Boolean>

    /**
     * Each target's commit id, positionally aligned with [targets], or empty when the source did not report them.
     * A [ChangeId] resolves to the visible commit of its change, so a remote ref left behind at a hidden commit
     * (jj-idea-wiz0, GitHub #130) can only be read through its commit id.
     */
    val commitIds: List<CommitId>

    /** Per-target hidden flag, positionally aligned with [commitIds]. */
    val hiddens: List<Boolean>

    /**
     * The first target, or `null` if [targets] is empty. A derived compat shim for callers that
     * only ever cared about "the" target before a conflicted ref could carry more than one - most
     * of them (navigation, selection sync) still only need this. A caller that must treat every
     * target as reachable (e.g. drag-and-drop, jj-idea-bico) should read [targets] directly.
     */
    val id: ChangeId? get() = targets.firstOrNull()

    /** Whether [id]'s target commit is immutable. `false` for an unresolved/deleted ref. */
    val immutable: Boolean get() = immutables.firstOrNull() ?: false

    /** The first target's commit id, or `null` if [commitIds] is empty. */
    val commitId: CommitId? get() = commitIds.firstOrNull()

    /** Whether [commitId]'s commit is hidden, i.e. no longer the visible commit of its change. */
    val hidden: Boolean get() = hiddens.firstOrNull() ?: false

    /** True when jj reports more than one target for this ref, i.e. it is conflicted/divergent. */
    val conflicted: Boolean get() = targets.size > 1
}
