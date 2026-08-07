package `in`.kkkev.jjidea.jj

/** Bounded so a stale bookmark far behind `@` can't turn this into an O(repo-size) query. */
private const val HEADS_LIMIT = 10
private const val DISTANCE_LIMIT = 1000

/**
 * The bookmark(s) nearest [to] as an ancestor, and how many changes separate them.
 *
 * @param names the nearest ancestor bookmark(s) of [to] — more than one when several bookmarks
 *   are equidistant (e.g. either side of a merge). Never empty.
 * @param distance the number of changes from the (a) nearest bookmark to [to], inclusive of [to].
 *   0 when a bookmark sits exactly on [to].
 * @param distanceCapped whether [distance] hit [DISTANCE_LIMIT] and is a lower bound, not exact.
 */
data class ClosestBookmarks(val names: List<BookmarkName>, val distance: Int, val distanceCapped: Boolean)

/**
 * Finds the bookmark(s) `jj bookmark advance` would move to reach [to] — the same
 * `heads(::to & bookmarks())` query jj's own `revsets.bookmark-advance-from` default uses — along
 * with the distance in changes. Two `jj log` calls, each bounded ([HEADS_LIMIT] /
 * [DISTANCE_LIMIT]) independent of total repo size, not a per-commit loop.
 *
 * Returns `null` when [to] has no ancestor bookmark at all, or either query fails.
 */
fun LogService.closestBookmarks(to: Revision = WorkingCopy): ClosestBookmarks? {
    val headsRevset = "heads(::$to & bookmarks())"
    val heads = getLogBasic(revset = Expression(headsRevset), limit = HEADS_LIMIT).getOrNull() ?: return null
    val names = heads.flatMap { it.bookmarks }.filterNot { it.isRemote }.map { it.name }.distinct()
    if (names.isEmpty()) return null

    val between = getLogBasic(revset = Expression("$headsRevset..$to"), limit = DISTANCE_LIMIT).getOrNull()
        ?: return null
    return ClosestBookmarks(names, between.size, distanceCapped = between.size >= DISTANCE_LIMIT)
}
