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
     * The first target, or `null` if [targets] is empty. A derived compat shim for callers that
     * only ever cared about "the" target before a conflicted ref could carry more than one - most
     * of them (navigation, selection sync) still only need this. A caller that must treat every
     * target as reachable (e.g. drag-and-drop, jj-idea-bico) should read [targets] directly.
     */
    val id: ChangeId? get() = targets.firstOrNull()

    /** Whether [id]'s target commit is immutable. `false` for an unresolved/deleted ref. */
    val immutable: Boolean get() = immutables.firstOrNull() ?: false

    /** True when jj reports more than one target for this ref, i.e. it is conflicted/divergent. */
    val conflicted: Boolean get() = targets.size > 1
}
