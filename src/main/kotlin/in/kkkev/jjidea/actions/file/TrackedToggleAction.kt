package `in`.kkkev.jjidea.actions.file

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ChangeListManager
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.logEntryForFile
import `in`.kkkev.jjidea.actions.singleRepoForFiles
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.vcs.ignore.JujutsuIgnoreService
import `in`.kkkev.jjidea.vcs.ignore.JujutsuIgnoredFilesService
import `in`.kkkev.jjidea.vcs.ignore.JujutsuTrackedFilesService

/**
 * Single checkbox-style toggle for `jj file track` / `jj file untrack`
 * (see docs/jj-track-untrack-model.md and jj-idea-i9ol's design notes). Checked = tracked,
 * unchecked = untracked; clicking flips it. Replaces separate Track/Untrack actions - "tracked"
 * is always reliably knowable (`jj file list`, authoritative), so there's no longer any need for
 * two actions with a text hint about which one is likely to work.
 *
 * Only visible when at least one selected file is predicted-ignored
 * ([trackedToggleVisible]) - this keeps the item rare/relevant rather than appearing on every
 * ordinary tracked file. **Folders are always excluded** (see [update]): jj happily expands a
 * directory fileset, but there's no tri-state checkbox in IntelliJ's menu system to represent
 * "partially tracked," and precisely computing "fully tracked" would need a real (if
 * folder-scoped) filesystem walk - not worth it for this feature.
 *
 * Tracked-state itself, once shown, is always determined reliably (never a guess) - but never by
 * blocking: `update()`/`isSelected()` run under a read action even on
 * [ActionUpdateThread.BGT] (confirmed via `OSProcessHandler.checkEdtAndReadAction`), so a
 * synchronous `jj file list` call there is forbidden. [resolveTrackedPaths] instead reads from
 * [JujutsuTrackedFilesService]'s cache (instant, non-blocking) and fires off a background refresh
 * on a miss, showing a safe "untracked" default until that lands.
 *
 * ## Click feedback (jj-idea-i9ol round 5)
 * [ToggleAction]'s popup stays open after a click by default
 * (`UISettings.keepPopupsForToggles`), and the platform flips the checkbox optimistically the
 * instant you click (`Toggleable.setSelected`). To avoid `isSelected()` silently reverting that
 * flip by re-reading a cache the async command hasn't updated yet, [setSelected] writes the
 * expected value into [JujutsuTrackedFilesService] *before* starting the command
 * (`setKnown`) - kept as-is on success, reverted on failure - rather than invalidating and waiting
 * on a fresh async round-trip either way. The command itself runs via `executeWithProgress`
 * (a real background-task indicator, matching git fetch/push) and always ends in a notification
 * balloon - success (`NotificationType.INFORMATION`) or failure (`NotificationType.ERROR`,
 * deliberately non-blocking here unlike most other actions' `tellUser` dialogs).
 *
 * Works in three contexts, same resolution as other file actions:
 * - Working copy panel / Commit view: resolves selection via SELECTED_CHANGES
 * - Project view / editor: resolves via VIRTUAL_FILE_ARRAY
 * - Commit details panel (historical context): hidden - tracking only applies to the working copy
 */
class TrackedToggleAction : ToggleAction(
    JujutsuBundle.message("action.tracked.toggle"),
    JujutsuBundle.message("action.tracked.toggle.description"),
    null
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)

        val project = e.project
        val repo = e.singleRepoForFiles
        val entry = e.logEntryForFile
        val isHistoricalContext = entry != null && !entry.isWorkingCopy
        val hasDirectory = e.filePaths.any { it.isDirectory }

        val anyPredictedIgnored = project != null &&
            repo != null &&
            !hasDirectory &&
            anySelectedIgnored(e.filePaths, repo.directory, JujutsuIgnoreService.getInstance(project)::isIgnored)

        e.presentation.isEnabledAndVisible = trackedToggleVisible(
            isHistoricalContext = isHistoricalContext,
            hasSingleRepo = repo != null,
            anyPredictedIgnored = anyPredictedIgnored
        )
    }

    override fun isSelected(e: AnActionEvent): Boolean = isFullyTracked(resolveTrackedPaths(e))

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        val repo = e.singleRepoForFiles ?: return
        val toChange = pathsNeedingChange(resolveTrackedPaths(e), targetState = state)
        if (toChange.isEmpty()) return

        val trackedService = JujutsuTrackedFilesService.getInstance(project)
        val errorKey = if (state) "action.track.files.error" else "action.untrack.files.error"
        val successTitleKey = if (state) {
            "action.tracked.toggle.track.success.title"
        } else {
            "action.tracked.toggle.untrack.success.title"
        }
        val verb = if (state) "Tracked" else "Untracked"

        // Optimistic write: makes isSelected() reflect the click immediately and durably, matching
        // (not racing) the platform's own optimistic checkbox flip - see TrackedToggleAction's doc
        // comment for why a plain invalidate-and-refresh would silently revert the checkbox.
        trackedService.setKnown(repo, toChange, tracked = state)

        val progressTitleKey = if (state) "progress.file.track" else "progress.file.untrack"

        repo.commandExecutor.createCommand { if (state) fileTrack(toChange) else fileUntrack(toChange) }
            .onSuccessResult {
                repo.invalidate(vfsChanged = true)
                JujutsuIgnoredFilesService.getInstance(project).invalidate(repo)
                // Deliberately NOT invalidating JujutsuTrackedFilesService here - the optimistic
                // write above is now confirmed correct for exactly these paths; wiping it would
                // reintroduce the same revert-to-unknown flicker on the success path instead.

                val summary = if (toChange.size == 1) {
                    JujutsuBundle.message("action.tracked.toggle.success.single", toChange.single().name)
                } else {
                    JujutsuBundle.message("action.tracked.toggle.success.multiple", toChange.size)
                }
                val message = "$verb $summary" + (stderr.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
                JujutsuNotifications.notify(
                    project,
                    JujutsuBundle.message(successTitleKey),
                    message,
                    NotificationType.INFORMATION
                )
            }
            .onFailure {
                trackedService.setKnown(repo, toChange, tracked = !state) // revert the optimistic write
                JujutsuNotifications.notify(
                    project,
                    JujutsuBundle.message("$errorKey.title"),
                    JujutsuBundle.message("$errorKey.message", stderr),
                    NotificationType.ERROR
                )
            }
            .executeWithProgress(project, JujutsuBundle.message(progressTitleKey))
    }

    /**
     * Resolves tracked state for the predicted-ignored subset of the current selection (bounded,
     * see [TRACKED_TOGGLE_SELECTION_LIMIT]). Empty selection, a directory in the selection, or
     * nothing predicted-ignored -> empty list, no jj call at all. Otherwise, per path: the
     * [JujutsuTrackedFilesService] cache first (instant, non-blocking, and authoritative once set
     * - including an optimistic write from a just-completed click, see [setSelected]); then a free
     * [ChangeListManager] shortcut for any file with a pending change (only tracked files can have
     * one); then a safe "untracked" default while a background refresh populates the cache for
     * next time.
     */
    private fun resolveTrackedPaths(e: AnActionEvent): List<TrackedPath> {
        val project = e.project ?: return emptyList()
        val repo = e.singleRepoForFiles ?: return emptyList()
        if (e.filePaths.any { it.isDirectory }) return emptyList()
        val ignoreService = JujutsuIgnoreService.getInstance(project)

        val predictedIgnored = e.filePaths.asSequence()
            .take(TRACKED_TOGGLE_SELECTION_LIMIT)
            .filter { ignoreService.isIgnored(it, repo.directory) }
            .toList()
        if (predictedIgnored.isEmpty()) return emptyList()

        val changeListManager = ChangeListManager.getInstance(project)
        val trackedService = JujutsuTrackedFilesService.getInstance(project)
        val unresolved = mutableListOf<FilePath>()
        val resolved = predictedIgnored.map { path ->
            // Cache first (authoritative once set, including a just-clicked optimistic write via
            // setKnown) - only fall back to the free ChangeListManager shortcut, then the safe
            // default, when the cache has no answer at all. Checking the free shortcut FIRST would
            // let a stale pending-change entry override a just-written optimistic value (e.g. right
            // after untracking a modified file, before ChangeListManager has re-run to notice).
            val tracked = trackedService.trackedStateOrNull(repo, path) ?: run {
                val hasPendingChange = path.virtualFile?.let { changeListManager.getChange(it) != null } == true
                if (hasPendingChange) {
                    true
                } else {
                    unresolved.add(path)
                    false // safe default until the async refresh below resolves it
                }
            }
            TrackedPath(path, tracked)
        }
        if (unresolved.isNotEmpty()) trackedService.requestRefresh(repo, unresolved)
        return resolved
    }
}
