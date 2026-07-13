package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile

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
