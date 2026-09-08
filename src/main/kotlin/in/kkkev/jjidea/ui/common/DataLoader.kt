package `in`.kkkev.jjidea.ui.common

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
}
