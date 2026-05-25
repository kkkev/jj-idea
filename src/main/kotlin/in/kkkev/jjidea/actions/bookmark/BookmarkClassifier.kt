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
            item.id?.shortenable?.full != targetId.shortenable.full
    }

    /**
     * Revset that resolves to the subset of [candidates] whose current targets are ancestors of [target]
     * (forward moves). Returns null when there are no candidates with known IDs.
     */
    fun ancestorRevset(candidates: List<BookmarkItem>, target: ChangeId): Expression? {
        val withIds = candidates.filter { it.id != null }
        if (withIds.isEmpty()) return null
        val ids = withIds.joinToString(" | ") { it.id!!.shortenable.full }
        return Expression("($ids) & ::${target.shortenable.full}")
    }

    /**
     * Partition [candidates] into FORWARD or BACKWARD_OR_SIDEWAYS.
     * Conflicted bookmarks are always BACKWARD_OR_SIDEWAYS regardless of [forwardIds].
     */
    fun classify(candidates: List<BookmarkItem>, forwardIds: Set<String>): List<ClassifiedBookmark> =
        candidates.map { item ->
            val direction = when {
                item.bookmark.conflict -> MoveDirection.BACKWARD_OR_SIDEWAYS
                item.id?.shortenable?.full in forwardIds -> MoveDirection.FORWARD
                else -> MoveDirection.BACKWARD_OR_SIDEWAYS
            }
            ClassifiedBookmark(item, direction)
        }
}
