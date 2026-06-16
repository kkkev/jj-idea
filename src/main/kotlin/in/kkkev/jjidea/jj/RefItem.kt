package `in`.kkkev.jjidea.jj

/**
 * Common interface for named repository references (bookmarks, tags) retrieved from listing commands.
 * Both [BookmarkItem] and [TagItem] are [RefItem]s, distinguishable by the [ref] type.
 */
sealed interface RefItem {
    /** The reference name, usable as a [Revision] in jj commands. */
    val ref: Ref

    /** Target change ID, or null if the reference points to nothing (e.g. deleted bookmark). */
    val id: ChangeId?

    /** Whether the target commit is immutable. Defaults to false for unresolved targets. */
    val immutable: Boolean
}
