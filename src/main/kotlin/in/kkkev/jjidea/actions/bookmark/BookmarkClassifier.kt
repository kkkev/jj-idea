package `in`.kkkev.jjidea.actions.bookmark

import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Expression

/**
 * FORWARD/BACKWARD_OR_SIDEWAYS classify a move against the bookmark's single current target.
 * RESOLVE is distinct: the bookmark is divergent (more than one current target, jj-idea-bico)
 * and the candidate is one of those existing targets - re-pointing at it resolves the
 * conflict, so it's always offered/selectable rather than gated behind the "allow
 * backward/sideways" checkbox the way an ordinary backward move is.
 */
enum class MoveDirection { FORWARD, BACKWARD_OR_SIDEWAYS, RESOLVE }

data class ClassifiedBookmark(val item: BookmarkItem, val direction: MoveDirection)

object BookmarkClassifier {
    /**
     * Local, present bookmarks that are not already at [targetId]. A divergent bookmark is only
     * excluded when [targetId] is its *sole* target (jj-idea-bico's rule, mirrored from
     * [in.kkkev.jjidea.ui.dnd.resolveDropOperation]'s self-drop check) - re-pointing it at just one of
     * several current targets is a legitimate resolve, not a no-op (jj-idea-t7cz).
     * Conflicted bookmarks are included but will be classified as BACKWARD_OR_SIDEWAYS or RESOLVE
     * by [classify], never FORWARD.
     * Deleted and remote bookmarks are excluded.
     */
    fun eligible(all: List<BookmarkItem>, targetId: ChangeId): List<BookmarkItem> = all.filter { item ->
        val bm = item.bookmark
        !bm.deleted &&
            !bm.isRemote &&
            item.targets.singleOrNull()?.full != targetId.full
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
     * Partition [candidates] into FORWARD, BACKWARD_OR_SIDEWAYS, or RESOLVE against [targetId] -
     * the change the move would land the bookmark on.
     * A divergent bookmark ([in.kkkev.jjidea.jj.RefItem.conflicted]) whose targets include [targetId]
     * classifies RESOLVE; any other conflicted bookmark is always BACKWARD_OR_SIDEWAYS regardless of
     * [forwardIds] (jj-idea-t7cz).
     */
    fun classify(
        candidates: List<BookmarkItem>,
        forwardIds: Set<String>,
        targetId: ChangeId
    ): List<ClassifiedBookmark> =
        candidates.map { item ->
            val direction = when {
                item.conflicted && item.targets.any { it.full == targetId.full } -> MoveDirection.RESOLVE
                item.bookmark.conflict -> MoveDirection.BACKWARD_OR_SIDEWAYS
                item.id?.full in forwardIds -> MoveDirection.FORWARD
                else -> MoveDirection.BACKWARD_OR_SIDEWAYS
            }
            ClassifiedBookmark(item, direction)
        }
}
