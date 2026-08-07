package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.ClosestBookmarks
import `in`.kkkev.jjidea.jj.JjFeature
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.disabledReasonIn
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.isSupportedIn

/**
 * Availability for a single, specific bookmark's "Advance" action ([advanceBookmarkAction]).
 * Separate from [ClosestAdvanceAvailability] because a specific bookmark is always a valid
 * target (unlike the "advance whichever is closest" action, which needs one to exist).
 */
internal enum class SingleAdvanceAvailability(val enabled: Boolean) {
    /** Deleted or remote bookmarks can't be advanced; jj bookmark advance only takes local ones. */
    NOT_APPLICABLE(enabled = false),
    UNSUPPORTED_VERSION(enabled = false),
    ENABLED(enabled = true)
}

internal fun singleAdvanceAvailability(bookmark: Bookmark, featureSupported: Boolean) = when {
    bookmark.deleted || bookmark.isRemote -> SingleAdvanceAvailability.NOT_APPLICABLE
    !featureSupported -> SingleAdvanceAvailability.UNSUPPORTED_VERSION
    else -> SingleAdvanceAvailability.ENABLED
}

/**
 * Advance a specific, already-known bookmark to `@` (`jj bookmark advance <name>` — a positional
 * name always targets that exact bookmark, regardless of `revsets.bookmark-advance-from`).
 * Offered alongside a bookmark's other actions; see [localBookmarkActions] and jj-idea-reiz.
 */
fun advanceBookmarkAction(repo: JujutsuRepository, bookmark: Bookmark): DumbAwareAction =
    object : DumbAwareAction(
        JujutsuBundle.message("action.bookmark.advance", bookmark.name),
        JujutsuBundle.message("action.bookmark.advance.tooltip", bookmark.name),
        AllIcons.Actions.Forward
    ) {
        override fun update(e: AnActionEvent) {
            val availability =
                singleAdvanceAvailability(bookmark, JjFeature.BOOKMARK_ADVANCE.isSupportedIn(repo.project))
            e.presentation.isEnabled = availability.enabled
            // A disabled menu item's tooltip is easy to miss (some platforms/menu styles never
            // show it), so the reason is also appended to the visible text itself — same pattern
            // as action.resolve.conflicts.needsEdit.
            e.presentation.text = when (availability) {
                SingleAdvanceAvailability.UNSUPPORTED_VERSION -> JujutsuBundle.message(
                    "action.bookmark.advance.disabled.version",
                    bookmark.name,
                    JjFeature.BOOKMARK_ADVANCE.minVersion
                )

                SingleAdvanceAvailability.NOT_APPLICABLE -> {
                    val key = if (bookmark.deleted) {
                        "action.bookmark.advance.disabled.deleted"
                    } else {
                        "action.bookmark.advance.disabled.remote"
                    }
                    JujutsuBundle.message(key, bookmark.name)
                }

                SingleAdvanceAvailability.ENABLED -> JujutsuBundle.message("action.bookmark.advance", bookmark.name)
            }
            e.presentation.description = when (availability) {
                SingleAdvanceAvailability.UNSUPPORTED_VERSION ->
                    JjFeature.BOOKMARK_ADVANCE.disabledReasonIn(repo.project)
                        ?: JujutsuBundle.message("action.bookmark.advance.tooltip", bookmark.name)

                else -> JujutsuBundle.message("action.bookmark.advance.tooltip", bookmark.name)
            }
        }

        override fun actionPerformed(e: AnActionEvent) = advanceBookmarks(repo, listOf(bookmark.name))

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

/**
 * Availability for the "advance whichever bookmark is closest" action
 * ([advanceClosestBookmarkAction]) — the #61 "button for jj bookmark advance" request.
 */
internal enum class ClosestAdvanceAvailability(val visible: Boolean, val enabled: Boolean) {
    /** No repository context (e.g. multi-root picker with nothing selected yet). */
    HIDDEN(visible = false, enabled = false),

    /** The working copy has no ancestor bookmark at all — nothing to advance. */
    NO_BOOKMARK(visible = true, enabled = false),
    UNSUPPORTED_VERSION(visible = true, enabled = false),
    ENABLED(visible = true, enabled = true)
}

internal fun closestAdvanceAvailability(
    hasRepo: Boolean,
    closest: ClosestBookmarks?,
    featureSupported: Boolean
): ClosestAdvanceAvailability = when {
    !hasRepo -> ClosestAdvanceAvailability.HIDDEN
    !featureSupported -> ClosestAdvanceAvailability.UNSUPPORTED_VERSION
    closest == null -> ClosestAdvanceAvailability.NO_BOOKMARK
    else -> ClosestAdvanceAvailability.ENABLED
}

/**
 * Advance whichever bookmark(s) are nearest `@` ([ClosestBookmarks], the same query jj's own
 * `revsets.bookmark-advance-from` default answers) — the #61 "button for jj bookmark advance"
 * request. A single nearest bookmark advances immediately; several equidistant ones (e.g. either
 * side of a merge) prompt a picker, per #61's own suggested behaviour.
 */
fun advanceClosestBookmarkAction(repo: JujutsuRepository?, closest: ClosestBookmarks?): DumbAwareAction =
    object : DumbAwareAction(
        JujutsuBundle.message("action.bookmark.advance.closest"),
        JujutsuBundle.message("action.bookmark.advance.closest.tooltip"),
        AllIcons.Actions.Forward
    ) {
        override fun update(e: AnActionEvent) {
            val featureSupported = repo != null && JjFeature.BOOKMARK_ADVANCE.isSupportedIn(repo.project)
            val availability = closestAdvanceAvailability(repo != null, closest, featureSupported)
            e.presentation.isVisible = availability.visible
            e.presentation.isEnabled = availability.enabled
            // See advanceBookmarkAction: a disabled item's tooltip can go unseen, so the reason is
            // also appended to the visible text.
            e.presentation.text = when (availability) {
                ClosestAdvanceAvailability.UNSUPPORTED_VERSION -> JujutsuBundle.message(
                    "action.bookmark.advance.closest.disabled.version",
                    JjFeature.BOOKMARK_ADVANCE.minVersion
                )

                ClosestAdvanceAvailability.NO_BOOKMARK ->
                    JujutsuBundle.message("action.bookmark.advance.closest.disabled.none.suffix")

                else -> JujutsuBundle.message("action.bookmark.advance.closest")
            }
            e.presentation.description = when (availability) {
                ClosestAdvanceAvailability.UNSUPPORTED_VERSION ->
                    repo?.let { JjFeature.BOOKMARK_ADVANCE.disabledReasonIn(it.project) }
                        ?: JujutsuBundle.message("action.bookmark.advance.closest.tooltip")

                ClosestAdvanceAvailability.NO_BOOKMARK ->
                    JujutsuBundle.message("action.bookmark.advance.closest.disabled.none")

                else -> JujutsuBundle.message("action.bookmark.advance.closest.tooltip")
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val target = repo ?: return
            val names = closest?.names ?: return
            if (names.size == 1) {
                advanceBookmarks(target, names)
            } else {
                AdvanceBookmarkPickerDialog.show(target, names) { chosen -> advanceBookmarks(target, chosen) }
            }
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

private fun advanceBookmarks(repo: JujutsuRepository, names: List<BookmarkName>) {
    repo.commandExecutor.createCommand { bookmarkAdvance(names) }
        .onSuccess { repo.invalidate() }
        .onFailure { tellUser(repo.project, "action.bookmark.advance.error") }
        .executeAsync()
}
