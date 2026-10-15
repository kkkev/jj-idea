package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.Revset

/**
 * Pure paging state for one repo's filtered log (jj-idea-ehwm.3): the matches of the compiled filter
 * revset [revset], fetched [pageSize] rows at a time in jj's log order. Holds no I/O -
 * [UnifiedJujutsuLogDataLoader] runs the queries this class describes and feeds the results back.
 *
 * Why this is not [PagedLogWindow]: that cursor tracks the *parents* of the rows shown so far, which is
 * only a correct resume point when [revset] is closed under ancestry (the base log). A filter is
 * sparse - a shown match's parent is usually not a match, and the ancestors of that parent include
 * matches that were already shown - so resuming from it re-fetches the same rows and never advances.
 *
 * Instead the cursor keeps the [frontier]: the heads of the matches not yet shown. The rows still to
 * come are exactly `revset & ::frontier`, so [pageRevset] fetches the next [pageSize] of them, and
 * [remainderRevset] (the same set minus the page just fetched) has the next frontier as its heads.
 * Log order lists children before parents, so what a page leaves behind is closed under ancestry
 * and `remainder == revset & ::heads(remainder)` holds, i.e. no match is skipped or repeated.
 * Cost: two `jj log` calls per page, with arguments bounded by the number of branches plus
 * [pageSize] - not by how much has already been shown.
 */
class FilterPageCursor(val revset: Revset, val pageSize: Int) {
    private val recordedPages = mutableListOf<List<LogEntry>>()
    private var frontier: Set<ChangeId> = emptySet()
    private var seeded = false

    /** All rows fetched so far, in page order. */
    val entries: List<LogEntry> get() = recordedPages.flatten()

    /** The most recently recorded page, or null before the first. */
    val lastPage: List<LogEntry>? get() = recordedPages.lastOrNull()

    val pageCount: Int get() = recordedPages.size

    /** True once every match has been fetched. False before [seed]. */
    val isExhausted: Boolean get() = seeded && frontier.isEmpty()

    /** Starts the walk from `heads(revset)`; must be called before [pageRevset]. */
    fun seed(heads: List<ChangeId>) {
        frontier = heads.toSet()
        seeded = true
    }

    /** True if the frontier is too wide to put in a query argument - see [PagedLogWindow.frontierTooWide]. */
    fun frontierTooWide(cap: Int) = frontier.size > cap

    /** The next page: the first [pageSize] rows (in log order) of the matches still to come. */
    fun pageRevset(): Revset {
        check(seeded) { "seed() must be called before pageRevset()" }
        check(frontier.isNotEmpty()) { "pageRevset() called on an exhausted cursor" }
        return Expression("($revset) & ::(${frontierTerms()})")
    }

    /**
     * What is left once [page] (the result of [pageRevset]) is taken off; its heads are the next
     * frontier. Rows go in by commit id, wrapped in `present()` so one rewritten between the two
     * calls resolves to nothing instead of failing the query.
     */
    fun remainderRevset(page: List<LogEntry>): Revset {
        val shown = page.joinToString(" | ") { "present(${it.commitId.full})" }
        return Expression("(($revset) & ::(${frontierTerms()})) ~ ($shown)")
    }

    /**
     * Records [page] and the heads of what remains after it ([remainingHeads], from
     * [remainderRevset]); empty means that was the last page.
     */
    fun recordPage(page: List<LogEntry>, remainingHeads: List<ChangeId>) {
        recordedPages.add(page)
        frontier = remainingHeads.toSet()
    }

    /** Records a final [page] with nothing after it - one shorter than [pageSize] needs no remainder query. */
    fun recordLastPage(page: List<LogEntry>) = recordPage(page, emptyList())

    // `.full` (not a bare change id) so a divergent id never fails outright; present() so a head
    // rewritten by a concurrent jj operation resolves to empty - see PagedLogWindow.pageRevset.
    private fun frontierTerms() = frontier.joinToString(" | ") { "present(${it.full})" }
}

/** [fetchNextPage]'s failure for a frontier too wide to query: the caller falls back to one unpaged fetch. */
internal class FrontierTooWide : Exception("log frontier too wide to page")

/**
 * Fetches the next page of this cursor's matches through [logService]: one `getLogHeads` to seed on
 * the first call, then the page itself and - unless the page came back short, which proves it was the
 * last - one more `getLogHeads` for what remains. jj's own error is kept in the failure; a frontier
 * wider than [PagedLogWindow.FRONTIER_CAP] fails with [FrontierTooWide]. Returns all rows so far.
 */
internal fun FilterPageCursor.fetchNextPage(logService: LogService): Result<List<LogEntry>> {
    if (pageCount == 0 && !isExhausted) {
        seed(logService.getLogHeads(revset).getOrElse { return Result.failure(it) })
    }
    if (isExhausted) return Result.success(entries)
    if (frontierTooWide(PagedLogWindow.FRONTIER_CAP)) return Result.failure(FrontierTooWide())
    val page = logService.getLog(pageRevset(), limit = pageSize, quiet = true).getOrElse { return Result.failure(it) }
    if (page.size < pageSize) {
        recordLastPage(page)
    } else {
        recordPage(page, logService.getLogHeads(remainderRevset(page)).getOrElse { return Result.failure(it) })
    }
    return Result.success(entries)
}
