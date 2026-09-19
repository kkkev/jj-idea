package `in`.kkkev.jjidea.jj

/**
 * Represents a bookmark with its name and associated change ID(s).
 * Used when listing bookmarks from `jj bookmark list`.
 *
 * [targets] holds every target jj reports for this bookmark (`added_targets`) - normally one, but
 * more than one for a conflicted/divergent bookmark (jj-idea-bico). The single-[ChangeId]
 * secondary constructor is kept for the many callers (tests included) that only ever deal with an
 * unconflicted bookmark.
 */
data class BookmarkItem(
    val bookmark: Bookmark,
    override val targets: List<ChangeId>,
    override val immutables: List<Boolean> = List(targets.size) { false }
) : RefItem {
    constructor(bookmark: Bookmark, id: ChangeId?, immutable: Boolean = false) :
        this(bookmark, listOfNotNull(id), if (id == null) emptyList() else listOf(immutable))

    override val ref: Ref get() = bookmark.name
}
