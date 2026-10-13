package `in`.kkkev.jjidea.ui.oplog

import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.ui.log.RootFilterSelection

/** One row of the Operations tab: an operation and the repository it belongs to. */
internal data class OperationRow(val repo: JujutsuRepository, val entry: OperationEntry)

/**
 * Merges the selected repositories' operation logs into one list, newest first. Repos hidden by
 * [selection] contribute nothing; [showSnapshots] is applied per repo. Ties on time break by the
 * order of [perRepo] and then by each repo's own order, so a repo's operations never reorder.
 */
internal fun mergeOperations(
    perRepo: Map<JujutsuRepository, List<OperationEntry>>,
    selection: RootFilterSelection,
    showSnapshots: Boolean
): List<OperationRow> = perRepo.entries
    .filter { selection.shows(it.key) }
    .flatMap { (repo, entries) -> entries.visibleOperations(showSnapshots).map { OperationRow(repo, it) } }
    // sortedByDescending is stable, so the flatMap order (repo order, then own order) breaks ties.
    .sortedByDescending { it.entry.time }

/**
 * Loads every repo in [repos] that [selection] shows, each through its own cached loader from
 * [loaderFor]. Excluded repos cost zero calls; each shown repo costs what [OperationLogLoader.load]
 * does (a head probe, plus one full query only when its head changed). Not thread-safe.
 */
internal fun loadShown(
    repos: List<JujutsuRepository>,
    selection: RootFilterSelection,
    loaderFor: (JujutsuRepository) -> OperationLogLoader
): Map<JujutsuRepository, OperationLogLoader.Result> =
    repos.filter(selection::shows).associateWith { loaderFor(it).load() }
