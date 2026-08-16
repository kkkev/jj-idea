package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications

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

/** `'main'`, or `'main', 'feature'` for several equidistant candidates — quoted, comma-joined. */
private fun quotedNames(names: List<BookmarkName>) = names.joinToString(", ") { "'${it.name}'" }

/**
 * The "Advance Bookmark Here" tooltip/description text, naming the actual bookmark(s) it would
 * move when known, rather than the generic "the nearest bookmark" — e.g. "Move 'main' forward to
 * the working copy (jj bookmark advance)". Falls back to the generic wording when [closest] is
 * `null` (nothing resolved yet, or nothing to advance — callers already show a different message
 * for that latter case via [ClosestAdvanceAvailability.NO_BOOKMARK], so this fallback is really
 * only hit before the first resolution). Pure so it's unit-testable without platform infra, same
 * as [closestAdvanceAvailability].
 */
internal fun closestAdvanceTooltip(closest: ClosestBookmarks?): String =
    closest?.let { JujutsuBundle.message("action.bookmark.advance.closest.tooltip.named", quotedNames(it.names)) }
        ?: JujutsuBundle.message("action.bookmark.advance.closest.tooltip")

/**
 * Advance whichever bookmark(s) are nearest `@` ([ClosestBookmarks]). A single nearest bookmark
 * advances immediately; several equidistant ones (e.g. either side of a merge) prompt a picker.
 *
 * By-value convenience overload for callers that rebuild their action group fresh each time it's
 * shown ([in.kkkev.jjidea.ui.log.JujutsuBookmarkWidget], [in.kkkev.jjidea.ui.log.JujutsuLogContextMenuActions]).
 * A long-lived host (a toolbar) must use the provider overload below instead, or the action
 * freezes onto whatever repo/bookmark was current at construction time.
 */
fun advanceClosestBookmarkAction(
    repo: JujutsuRepository?,
    closest: ClosestBookmarks?,
    confirmSingle: Boolean = false
): DumbAwareAction = advanceClosestBookmarkAction({ repo }, { closest }, confirmSingle)

/**
 * Provider-based form for a long-lived host (jj-idea-xsa8's Working Copy toolbar) that constructs
 * this action once and keeps the instance: `repo`/`closest` are re-read on every
 * [update]/[actionPerformed] rather than captured at construction time. Reconstructing a delegate
 * action on every refresh instead violates `ActionToolbarImpl`'s requirement that a toolbar's
 * action instances stay stable across refreshes.
 *
 * [confirmSingle]: also ask Yes/No before the single-candidate path (which otherwise advances
 * with no dialog). The equidistant-candidates path already gets a confirmation via
 * [AdvanceBookmarkPickerDialog]'s picker. Off by default for the popup call sites; the toolbar
 * turns it on, since a misclick on an icon-only button there would otherwise advance unconfirmed.
 */
fun advanceClosestBookmarkAction(
    repo: () -> JujutsuRepository?,
    closest: () -> ClosestBookmarks?,
    confirmSingle: Boolean = false
): DumbAwareAction =
    object : DumbAwareAction(
        JujutsuBundle.message("action.bookmark.advance.closest"),
        JujutsuBundle.message("action.bookmark.advance.closest.tooltip"),
        AllIcons.Actions.Forward
    ) {
        override fun update(e: AnActionEvent) {
            val currentRepo = repo()
            val currentClosest = closest()
            val featureSupported = currentRepo != null && JjFeature.BOOKMARK_ADVANCE.isSupportedIn(currentRepo.project)
            val availability = closestAdvanceAvailability(currentRepo != null, currentClosest, featureSupported)
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
                    currentRepo?.let { JjFeature.BOOKMARK_ADVANCE.disabledReasonIn(it.project) }
                        ?: JujutsuBundle.message("action.bookmark.advance.closest.tooltip")

                ClosestAdvanceAvailability.NO_BOOKMARK ->
                    JujutsuBundle.message("action.bookmark.advance.closest.disabled.none")

                else -> closestAdvanceTooltip(currentClosest)
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            // update() already hides/disables this action whenever repo()/closest() is null, so
            // the platform shouldn't invoke actionPerformed while either is - null here means
            // update() and actionPerformed() disagreed about state, not a reachable user path.
            val target = checkNotNull(repo()) { "Advance performed with no repo bound" }
            val names = checkNotNull(closest()) { "Advance performed with nothing to advance" }.names
            if (names.size == 1) {
                if (confirmSingle && !confirmAdvance(target, names.single())) return
                advanceBookmarks(target, names)
            } else {
                AdvanceBookmarkPickerDialog.show(target, names) { chosen -> advanceBookmarks(target, chosen) }
            }
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

/** Yes/No confirmation before an unconfirmed, dialog-less advance — see `confirmSingle` above. */
private fun confirmAdvance(repo: JujutsuRepository, name: BookmarkName) = Messages.showYesNoDialog(
    repo.project,
    JujutsuBundle.message("action.bookmark.advance.confirm.message", quotedNames(listOf(name))),
    JujutsuBundle.message("action.bookmark.advance.confirm.title"),
    Messages.getWarningIcon()
) == Messages.YES

/**
 * Advances [names] to `@` and notifies on success, since advancing otherwise has no visible
 * effect anywhere and no dialog to imply it worked. `to` defaults to `WorkingCopy` for every
 * caller here, so `repo.workingCopy` read before the command runs is already the destination.
 */
private fun advanceBookmarks(repo: JujutsuRepository, names: List<BookmarkName>) {
    val target = repo.workingCopy.id.short
    repo.commandExecutor.createCommand { bookmarkAdvance(names) }
        .onSuccess {
            repo.invalidate()
            JujutsuNotifications.notify(
                repo.project,
                JujutsuBundle.message("action.bookmark.advance.success.title"),
                JujutsuBundle.message("action.bookmark.advance.success.message", quotedNames(names), target),
                NotificationType.INFORMATION
            )
        }
        .onFailure { tellUser(repo.project, "action.bookmark.advance.error") }
        .executeAsync()
}
