package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.filePaths
import `in`.kkkev.jjidea.actions.singleRepoForFiles
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.ignore.JujutsuIgnoreService
import `in`.kkkev.jjidea.vcs.ignore.JujutsuTrackedFilesService

/**
 * Upper bound on how many selected paths a single Tracked-toggle menu-open inspects (for the
 * ignored-prediction check, and separately for the tracked-state query) before giving up on the
 * rest. `update()` runs on BGT so this can't stall the EDT, but a very large multi-selection
 * (e.g. select-all in a huge Project view) shouldn't do unbounded work per menu paint either -
 * see contributing.md's refresh-path rules. In practice this only ever bounds the
 * *predicted-ignored* subset of the selection (see [trackedToggleVisible]), which is normally 0-1
 * files - not the whole selection or, still less, the whole repo. See
 * docs/jj-track-untrack-model.md for why "ignored" is a heuristic but "tracked" (via
 * `jj file list`) never needs to be.
 */
internal const val TRACKED_TOGGLE_SELECTION_LIMIT = 100

/** Bounded "is any of these paths ignored" check - see [TRACKED_TOGGLE_SELECTION_LIMIT]. */
internal fun anySelectedIgnored(
    paths: List<FilePath>,
    repoRoot: VirtualFile,
    isIgnored: (FilePath, VirtualFile) -> Boolean
) = paths.asSequence().take(TRACKED_TOGGLE_SELECTION_LIMIT).any { isIgnored(it, repoRoot) }

/**
 * Whether the Tracked toggle belongs in the menu at all - a single-repo working-copy context
 * where at least one selected file is predicted-ignored. Gating on the ignore prediction (rather
 * than showing unconditionally) keeps the item rare/relevant instead of appearing on every
 * ordinary tracked file; the residual risk is a file ignored only via global git excludes (which
 * our prediction can't see - see jj-idea-k5vy) staying hidden, with "add it to the project's own
 * .gitignore" as the workaround until that's fixed.
 */
internal fun trackedToggleVisible(isHistoricalContext: Boolean, hasSingleRepo: Boolean, anyPredictedIgnored: Boolean) =
    !isHistoricalContext && hasSingleRepo && anyPredictedIgnored

/**
 * A selected path plus jj's own answer to whether it's currently tracked (`jj file list`,
 * authoritative - see docs/jj-track-untrack-model.md). Only ever built for the
 * predicted-ignored subset of a selection, so never more than [TRACKED_TOGGLE_SELECTION_LIMIT]
 * entries.
 */
internal data class TrackedPath(val path: FilePath, val tracked: Boolean)

/**
 * Checkbox state for the Tracked toggle: checked iff every path in the (already
 * ignored-filtered) selection is tracked. A mixed selection (some tracked, some not) reads as
 * unchecked - there's still something left to track.
 */
internal fun isFullyTracked(paths: List<TrackedPath>): Boolean = paths.isNotEmpty() && paths.all { it.tracked }

/**
 * Partitions [paths] into the subset that actually needs to change to reach [targetState] -
 * `true` selects the untracked members (need `jj file track`), `false` selects the tracked
 * members (need `jj file untrack`). Only this subset is ever passed to the underlying jj command,
 * so a single click never re-tracks/re-untracks a file that's already in the target state.
 */
internal fun pathsNeedingChange(paths: List<TrackedPath>, targetState: Boolean): List<FilePath> =
    paths.filter { it.tracked != targetState }.map { it.path }

/**
 * jj-idea-otwq: whether a file-scoped action should be disabled because the selection consists
 * solely of files that are predicted-ignored *and* known to be untracked - jj has no history,
 * diff or restore to offer for those. Fails open (returns `false`, so the action stays enabled)
 * whenever it can't be sure: an empty selection, one beyond [TRACKED_TOGGLE_SELECTION_LIMIT], any
 * directory, any path not predicted-ignored (short-circuits before [trackedState] is consulted),
 * or any path whose tracked state is unknown (`null` - e.g. a cache miss awaiting refresh; a
 * tracked-but-ignored file must never be wrongly disabled).
 */
internal fun selectionIsOnlyUntrackedIgnored(
    paths: List<FilePath>,
    repoRoot: VirtualFile,
    isIgnored: (FilePath, VirtualFile) -> Boolean,
    trackedState: (FilePath) -> Boolean?
): Boolean {
    if (paths.isEmpty() || paths.size > TRACKED_TOGGLE_SELECTION_LIMIT) return false
    return paths.all { !it.isDirectory && isIgnored(it, repoRoot) && trackedState(it) == false }
}

/**
 * Tracked state for each of [paths] (all predicted-ignored): the [JujutsuTrackedFilesService]
 * cache first (authoritative once set, including an optimistic write from a just-completed
 * click); then a free [ChangeListManager] shortcut (only tracked files can have a pending
 * change); otherwise `null` (unknown), with one batched background refresh requested so the next
 * call can answer. Never blocks - safe in `update()`.
 */
internal fun resolveTrackedStates(
    project: Project,
    repo: JujutsuRepository,
    paths: List<FilePath>
): List<Pair<FilePath, Boolean?>> {
    val changeListManager = ChangeListManager.getInstance(project)
    val trackedService = JujutsuTrackedFilesService.getInstance(project)
    val unresolved = mutableListOf<FilePath>()
    val resolved = paths.map { path ->
        // Cache first: checking the pending-change shortcut first would let a stale entry override
        // a just-written optimistic value (e.g. right after untracking a modified file).
        val tracked = trackedService.trackedStateOrNull(repo, path) ?: run {
            val hasPendingChange = path.virtualFile?.let { changeListManager.getChange(it) != null } == true
            if (hasPendingChange) true else null.also { unresolved.add(path) }
        }
        path to tracked
    }
    if (unresolved.isNotEmpty()) trackedService.requestRefresh(repo, unresolved)
    return resolved
}

/**
 * Call at the end of a file-scoped action's `update()`: leaves the action visible but disables it
 * (with an explanatory description) when [selectionIsOnlyUntrackedIgnored]. See that function for
 * the fail-open rules and complexity (<= [TRACKED_TOGGLE_SELECTION_LIMIT] ignore lookups; tracked
 * lookups only once every path is predicted-ignored).
 */
internal fun AnActionEvent.disableIfOnlyUntrackedIgnored() {
    if (!presentation.isEnabled) return
    val project = project ?: return
    val repo = singleRepoForFiles ?: return
    if (isOnlyUntrackedIgnored(project, repo)) {
        presentation.isEnabled = false
        presentation.description = JujutsuBundle.message("action.disabled.untracked.ignored")
    }
}

internal fun AnActionEvent.isOnlyUntrackedIgnored(project: Project, repo: JujutsuRepository): Boolean {
    val ignoreService = JujutsuIgnoreService.getInstance(project)
    return selectionIsOnlyUntrackedIgnored(
        filePaths,
        repo.directory,
        ignoreService::isIgnored,
        trackedState = { resolveTrackedStates(project, repo, listOf(it)).single().second }
    )
}
