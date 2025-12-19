package `in`.kkkev.jjidea.jj

/**
 * Represents a bookmark with its name and associated change ID.
 * Used when listing bookmarks from `jj bookmark list`.
 */
data class BookmarkItem(
    val bookmark: Bookmark,
    val changeId: ChangeId
)
