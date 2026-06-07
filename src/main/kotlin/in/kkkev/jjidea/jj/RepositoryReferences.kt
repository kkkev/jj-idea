package `in`.kkkev.jjidea.jj

/**
 * Bookmarks and tags for a single repository, loaded and invalidated as a unit.
 *
 * jj exposes bookmarks and tags through separate CLI commands, but every consumer reads them
 * together and every invalidation path refreshes them together, so the state model carries them
 * as one value rather than two parallel states.
 *
 * @see in.kkkev.jjidea.jj.JujutsuStateModel.references
 */
data class RepositoryReferences(
    val bookmarks: List<BookmarkItem> = emptyList(),
    val tags: List<TagItem> = emptyList()
)
