package `in`.kkkev.jjidea.ui.common

import `in`.kkkev.jjidea.ui.log.PagedLogWindow

/**
 * A data loader for a panel containing a commit table.
 */
interface DataLoader {
    fun load()
    fun refresh()

    /** Clear any per-repo expansion state accumulated by navigation. Called on explicit Refresh. */
    fun clearExpansions() {}

    /**
     * Explicit-Refresh entry point (jj-idea-2c8k) — distinct from [refresh] because a paged
     * loader's [refresh] is deliberately a *cheap* page-1-only reconcile (the post-write path),
     * while an explicit Refresh click should re-verify everything currently loaded, preserving
     * scroll depth rather than collapsing back to page 1. Defaults to [refresh] for loaders that
     * don't page (there's only one kind of refresh for them).
     */
    fun forceRefresh() = refresh()

    /**
     * Load one more page of history (jj-idea-2c8k) — scroll-triggered, or called eagerly ahead
     * of the visible viewport. No-op for loaders that don't page.
     */
    fun loadMore() {}

    /**
     * How many rows from the bottom of the loaded table scrolling should trigger [loadMore]: about
     * one page, so the next page is fetched before the user reaches the end. Grows with a loader
     * whose pages grow (jj-idea-2570.9).
     */
    val loadMoreDistance: Int get() = PagedLogWindow.PAGE_ROWS

    /** Release any background work the loader started (e.g. the paged log's idle trickle). */
    fun close() {}
}
