package `in`.kkkev.jjidea.jj

/** Bounded so a stale bookmark far behind `@` can't turn this into an O(repo-size) query. */
private const val HEADS_LIMIT = 10
private const val DISTANCE_LIMIT = 1000

/** Bounded so a repo with many forgotten heads can't turn this into an unbounded fan-out. */
private const val DANGLING_HEADS_LIMIT = 10

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
 * The distance query is restricted to `descendants(heads(::to & bookmarks()))` in addition to the
 * plain `heads..to` range: for a merge where [to] has one bookmarked parent and one unrelated,
 * unbookmarked parent, plain `X..Y` (`::Y & ~::X`) also pulls in the *entire* unrelated parent's
 * ancestry — none of it is an ancestor of the bookmark either — wildly overcounting the distance
 * (jj-idea-lig7 follow-up). Restricting to descendants of the bookmark itself keeps only the
 * commits actually on the path from it to [to].
 *
 * Returns `null` when [to] has no ancestor bookmark at all, or either query fails.
 */
fun LogService.closestBookmarks(to: Revision = WorkingCopy): ClosestBookmarks? {
    val headsRevset = "heads(::$to & bookmarks())"
    val heads = getLogBasic(revset = Expression(headsRevset), limit = HEADS_LIMIT).getOrNull() ?: return null
    val names = heads.flatMap { it.bookmarks }.filterNot { it.isRemote }.map { it.name }.distinct()
    if (names.isEmpty()) return null

    val betweenRevset = "descendants($headsRevset) & ($headsRevset..$to)"
    val between = getLogBasic(revset = Expression(betweenRevset), limit = DISTANCE_LIMIT).getOrNull()
        ?: return null
    return ClosestBookmarks(names, between.size, distanceCapped = between.size >= DISTANCE_LIMIT)
}

/**
 * A visible head with no bookmark on it (jj-idea-lig7, GitHub #107): work sitting past a
 * forgotten `jj bookmark advance`. Because `heads()` are maximal, "no bookmark reachable
 * forward from H" collapses to "no bookmark sits on H" — no `::` ancestor walk needed.
 *
 * @param closest the nearest ancestor bookmark(s) behind [id] and how far behind, or `null`
 *   when [id] has no ancestor bookmark at all (e.g. a root-adjacent head).
 */
data class DanglingHead(val id: ChangeId, val closest: ClosestBookmarks?)

/**
 * Finds dangling heads (see [DanglingHead]), each with its own [closestBookmarks] lookup, reusing
 * that existing plumbing rather than a new traversal (per the bead's ask). One bounded `jj log`
 * call for the heads themselves, plus two more per dangling head (bounded by [HEADS_LIMIT] /
 * [DISTANCE_LIMIT]) — see `DanglingHeadsScaleTest`. The working-copy head is excluded: the
 * existing `[closest] +n @` row already covers it.
 *
 * Returns an empty list when the heads query fails, matching [closestBookmarks]'s "degrade
 * quietly" posture rather than surfacing an error in the panel.
 */
fun LogService.danglingHeads(limit: Int = DANGLING_HEADS_LIMIT): List<DanglingHead> {
    val revset = "heads(all()) ~ (bookmarks() | remote_bookmarks())"
    val heads = getLogBasic(revset = Expression(revset), limit = limit).getOrNull() ?: return emptyList()
    return heads.filterNot { it.isWorkingCopy }.map { DanglingHead(it.id, closestBookmarks(to = it.id)) }
}
