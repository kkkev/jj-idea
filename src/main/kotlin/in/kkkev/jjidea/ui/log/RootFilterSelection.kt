package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.JujutsuRepository

/**
 * Tri-state root display filter (jj-idea-qcks, GitHub #96): each repository root is either
 * unset, [included], or [excluded]. A root is never in both sets.
 *
 * If [included] is non-empty, only included roots show (allowlist) - a repository not in
 * either set (e.g. mapped after this filter was set, see `JujutsuStateModel`'s live
 * `VcsListener` reaction) defaults to hidden, matching a deliberately narrowed selection.
 * Otherwise, if [excluded] is non-empty, everything but the excluded roots shows
 * (denylist/mute-list) - a repository in neither set defaults to shown, matching "hide a
 * couple of noisy repos, show everything else". With both empty, everything shows.
 */
data class RootFilterSelection(
    val included: Set<JujutsuRepository> = emptySet(),
    val excluded: Set<JujutsuRepository> = emptySet()
) {
    val isActive: Boolean get() = included.isNotEmpty() || excluded.isNotEmpty()

    /** Whether [repo]'s entries should be shown under this filter. */
    fun shows(repo: JujutsuRepository): Boolean = when {
        included.isNotEmpty() -> repo in included
        excluded.isNotEmpty() -> repo !in excluded
        else -> true
    }
}
