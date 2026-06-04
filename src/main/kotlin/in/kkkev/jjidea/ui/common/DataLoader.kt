package `in`.kkkev.jjidea.ui.common

/**
 * A data loader for a panel containing a commit table.
 */
interface DataLoader {
    fun load()
    fun refresh()

    /** Clear any per-repo expansion state accumulated by navigation. Called on explicit Refresh. */
    fun clearExpansions() {}
}
