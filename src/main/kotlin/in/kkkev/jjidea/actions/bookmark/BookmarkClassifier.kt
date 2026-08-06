package `in`.kkkev.jjidea.actions.bookmark

import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Expression

enum class MoveDirection { FORWARD, BACKWARD_OR_SIDEWAYS }

data class ClassifiedBookmark(val item: BookmarkItem, val direction: MoveDirection)

object BookmarkClassifier {
    /**
     * Local, present bookmarks that are not already at [targetId].
     * Conflicted bookmarks are included but will be classified as BACKWARD_OR_SIDEWAYS regardless.
     * Deleted and remote bookmarks are excluded.
     */
    fun eligible(all: List<BookmarkItem>, targetId: ChangeId): List<BookmarkItem> = all.filter { item ->
        val bm = item.bookmark
        !bm.deleted &&
            !bm.isRemote &&
            item.id?.full != targetId.full
    }

    /**
     * Revset that resolves to the subset of [candidates] whose current targets are ancestors of [target]
     * (forward moves). Returns null when there are no candidates with known IDs.
     *
     * IDs are offset-qualified (via [ChangeId.full]) rather than the bare [ChangeId.shortenable.full], because a
     * single divergent change id in an unqualified union makes the whole `jj log` query fail (jj refuses to resolve
     * an ambiguous change id even inside a larger expression).
     */
    fun ancestorRevset(candidates: List<BookmarkItem>, target: ChangeId): Expression? {
        val withIds = candidates.filter { it.id != null }
        if (withIds.isEmpty()) return null
        val ids = withIds.joinToString(" | ") { it.id!!.full }
        return Expression("($ids) & ::${target.full}")
    }

    /**
     * Revset that resolves to the descendants of [from] (inclusive) — used to classify candidate destinations for
     * a fixed bookmark as forward (descendant) vs backward/sideways (not a descendant).
     */
    fun descendantRevset(from: ChangeId): Expression = Expression("${from.full}::")

    /**
     * Partition [candidates] into FORWARD or BACKWARD_OR_SIDEWAYS.
     * Conflicted bookmarks are always BACKWARD_OR_SIDEWAYS regardless of [forwardIds].
     */
    fun classify(candidates: List<BookmarkItem>, forwardIds: Set<String>): List<ClassifiedBookmark> =
        candidates.map { item ->
            val direction = when {
                item.bookmark.conflict -> MoveDirection.BACKWARD_OR_SIDEWAYS
                item.id?.full in forwardIds -> MoveDirection.FORWARD
                else -> MoveDirection.BACKWARD_OR_SIDEWAYS
            }
            ClassifiedBookmark(item, direction)
        }
}
