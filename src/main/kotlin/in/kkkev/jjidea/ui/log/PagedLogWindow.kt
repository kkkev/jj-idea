package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revset

/**
 * Pure paging/frontier-cursor state for one repo's paged log window (jj-idea-2c8k, GitHub #69).
 * See docs/design/jj-idea-2c8k-paged-log-loading.md for the full mechanism, correctness
 * invariant, and validation data — this class implements exactly that mechanism.
 *
 * Holds no I/O — [UnifiedJujutsuLogDataLoader] drives it: read [baseRevset]/[pageRevset] to know
 * what to fetch, run the actual `jj log` call, then feed the result back via [seed]/[recordPage].
 * This mirrors the extraction-for-testability pattern already used for
 * [in.kkkev.jjidea.actions.undo.resolveUndoLastOperationPresentation] — pure decision logic,
 * unit-testable without a real jj process or IntelliJ platform.
 *
 * Correctness invariant (see the design doc for the proof): after every [recordPage] call,
 * the union of all recorded pages' entries equals `revset \ ancestors(frontier)`. [frontier]
 * must be seeded from a complete, unrestricted `heads(revset)` for this to hold — an incomplete
 * or incrementally-inferred seed can silently drop entire branches (a bug found and fixed during
 * design; see the doc's "carry-forward-vs-recompute-fresh" note).
 */
class PagedLogWindow(val baseRevset: Revset, val pageSize: Int) {
    private val recordedPages = mutableListOf<List<LogEntry>>()
    private val shown = mutableSetOf<ChangeId>()
    private var frontier: Set<ChangeId> = emptySet()
    private var seeded = false

    /** All entries loaded so far, in page order. */
    val entries: List<LogEntry> get() = recordedPages.flatten()

    /**
     * Each recorded page, in order — exposed (read-only) so the loader can replay already-known
     * pages back through a fresh [seed]+[recordPage] sequence (e.g. splicing in a freshly
     * refetched page 1 ahead of already-loaded deeper pages, or re-walking a known [pageCount]
     * of pages from scratch) without needing bespoke mutation methods on this class.
     */
    val pages: List<List<LogEntry>> get() = recordedPages

    /** How many pages have been fetched so far. */
    val pageCount: Int get() = recordedPages.size

    /**
     * True once [seed] has been called and the frontier has fully drained — every commit
     * matching [baseRevset] has been fetched. False before [seed] is called.
     */
    val isExhausted: Boolean get() = seeded && frontier.isEmpty()

    /**
     * Seed (or reseed) the frontier from a complete `heads(baseRevset)` result — call
     * [in.kkkev.jjidea.jj.LogService.getLogHeads] with [baseRevset] to get it. Must be called
     * before the first [pageRevset]/[recordPage].
     */
    fun seed(heads: List<ChangeId>) {
        frontier = heads.toSet()
        seeded = true
    }

    /**
     * True if the frontier is wide enough that constructing [pageRevset] risks the OS
     * argument-length ceiling and/or the jj-internal performance cliff documented in the design
     * doc (both observed between ~15,000 and ~30,000 ids in testing) — caller should fall back
     * to a full, non-paged reload instead of calling [pageRevset].
     */
    fun frontierTooWide(cap: Int) = frontier.size > cap

    /**
     * Revset for the next page's fetch: `baseRevset` intersected with the frontier and its
     * ancestors. Each frontier id is wrapped in `present(...)`, matching
     * [in.kkkev.jjidea.jj.LogCache.loadContext]'s existing convention (GitHub #76) — a frontier
     * member abandoned or rewritten by a concurrent jj operation between page fetches resolves
     * to empty instead of failing the whole query, and `.full` (not a bare change id) so a
     * divergent id never fails outright (see the design doc).
     */
    fun pageRevset(): Revset {
        check(seeded) { "seed() must be called before pageRevset()" }
        check(frontier.isNotEmpty()) { "pageRevset() called with an empty frontier — already exhausted" }
        val terms = frontier.joinToString(" | ") { "present(${it.full})" }
        return Expression("($baseRevset) & (($terms) | ancestors($terms))")
    }

    /**
     * Feed a page's fetch result (from [pageRevset]) back in. Updates the frontier — every
     * parent id not yet shown is folded in, every id shown in [page] is dropped — and appends
     * [page] to [entries].
     *
     * An empty [page] terminates the walk (clears the frontier) instead of leaving it unchanged.
     * [pageRevset] is `baseRevset & (frontier | ancestors(frontier))`, over the *entire* matching
     * set (`--limit` only ever truncates, never explains away a smaller true count) — so an empty
     * result *proves* nothing in any currently-tracked branch's ancestry matches `baseRevset`,
     * however deep. This matters whenever `baseRevset` is a filter that isn't closed under
     * ancestry (e.g. `bookmarks()`, matching only specific commits, not their history) — a
     * frontier member's true parent can be real but permanently excluded by the filter, so it
     * would otherwise never move into `shown` and never leave the frontier: [isExhausted] would
     * never become true, and a caller looping until it is would loop forever. Found via
     * `PagedLogWindowContractTest`'s custom-revset case, which hung until it exhausted the JVM
     * heap before this fix.
     */
    fun recordPage(page: List<LogEntry>) {
        recordedPages.add(page)
        if (page.isEmpty()) {
            frontier = emptySet()
            return
        }
        page.forEach { shown.add(it.id) }
        val newFrontier = mutableSetOf<ChangeId>()
        for (entry in page) {
            for (parentId in entry.parentIds) {
                if (parentId !in shown) newFrontier.add(parentId)
            }
        }
        frontier = (frontier + newFrontier) - shown
    }

    /**
     * Ids among [entries] whose own parent(s) are not yet loaded (in [frontier]) — the set a
     * renderer must not treat as "this is a true root," and the set the loader's eager
     * prefetch policy exists to resolve before the user scrolls to them. See the design doc's
     * "When to load more" / "Graph rendering at page/window boundaries."
     */
    fun idsWithUnresolvedParent(): Set<ChangeId> =
        entries.asSequence()
            .filter { entry -> entry.parentIds.any { it in frontier } }
            .mapTo(mutableSetOf()) { it.id }

    /** Clear all state (pages, frontier, seed) so this window can be re-walked from scratch. */
    fun reset() {
        recordedPages.clear()
        shown.clear()
        frontier = emptySet()
        seeded = false
    }

    companion object {
        /**
         * Safety-valve threshold for [frontierTooWide] — a repo whose frontier ever exceeds this
         * is already in a regime where even a trivial jj query is multi-second (confirmed at
         * ~30,000 total repo heads) and risks the OS argument-length ceiling (`ARG_MAX`,
         * confirmed to fail around 20,000 ids). 8,000 is the most rigorously double-checked
         * clean data point below both limits — see docs/design/jj-idea-2c8k-paged-log-loading.md
         * § "Where it actually breaks."
         */
        const val FRONTIER_CAP = 8000
    }
}
