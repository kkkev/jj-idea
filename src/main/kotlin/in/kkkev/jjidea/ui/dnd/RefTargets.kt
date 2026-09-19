package `in`.kkkev.jjidea.ui.dnd

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.stateModel

/**
 * Every target [bookmark] currently has, per the loaded reference state, or `setOf(fallback)` when
 * that state hasn't loaded yet or no longer lists the bookmark (a stale hit-test mid-refresh). Used
 * to populate [DragPayload.BookmarkRef.targets] at the two chip-based drag surfaces (the log table
 * and the commit details panel) that only know the row they're drawn on, not the bookmark's full
 * target set - unlike the bookmarks panel, whose node already holds the [in.kkkev.jjidea.jj.BookmarkItem]
 * (jj-idea-bico).
 *
 * Reads [in.kkkev.jjidea.util.NotifiableState.cachedValue], never `.value` - this runs on the EDT
 * inside a mouse-driven hit-test (bean/image provider), which must never block on a load.
 */
internal fun JujutsuRepository.bookmarkTargets(bookmark: Bookmark, fallback: ChangeId): Set<ChangeId> =
    project.stateModel.references.cachedValue[this]
        ?.bookmarks?.firstOrNull { it.bookmark.name == bookmark.name }
        ?.targets?.toSet()?.takeIf { it.isNotEmpty() }
        ?: setOf(fallback)

/** As [bookmarkTargets], but for a tag. */
internal fun JujutsuRepository.tagTargets(tag: Tag, fallback: ChangeId): Set<ChangeId> =
    project.stateModel.references.cachedValue[this]
        ?.tags?.firstOrNull { it.tag == tag }
        ?.targets?.toSet()?.takeIf { it.isNotEmpty() }
        ?: setOf(fallback)
