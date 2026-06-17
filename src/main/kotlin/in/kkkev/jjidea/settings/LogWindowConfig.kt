package `in`.kkkev.jjidea.settings

import `in`.kkkev.jjidea.jj.JujutsuRepository

/**
 * Configuration for a single log window tab.
 *
 * Persisted in [JujutsuSettingsState.logWindows] (xmlb bean serialisation, same pattern as [RepositoryConfig]).
 * `id = "default"` is reserved for the auto-created tab; user-created tabs use UUID strings.
 * [selectedRepoPaths] empty means "all repos".
 */
data class LogWindowConfig(
    var id: String = "",
    var name: String = "",
    var selectedRepoPaths: MutableList<String> = mutableListOf(),
    var detailsOnRight: Boolean = true,
    // Column visibility — defaults match JujutsuColumnManager field values
    var showAuthorColumn: Boolean = true,
    var showCommitterColumn: Boolean = false,
    var showDateColumn: Boolean = true,
    var showStatus: Boolean = true,
    var showChangeId: Boolean = true,
    var showDescription: Boolean = true,
    var showDecorations: Boolean = true,
    // Column widths (keyed by JujutsuLogTableModel.KEY_* constants)
    var columnWidths: MutableMap<String, Int> = mutableMapOf(),
    // Filter / search state
    var searchText: String = "",
    var useRegex: Boolean = false,
    var matchCase: Boolean = false,
    var matchWholeWords: Boolean = false,
    var authorFilter: MutableList<String> = mutableListOf(),
    /** Name of a [in.kkkev.jjidea.ui.log.JujutsuDateFilterComponent.DatePeriod] enum constant, or "" for none. */
    var dateFilterPeriodName: String = "",
    /** Name of the selected reference (bookmark, tag, or "@"), or "" for none. */
    var selectedReference: String = "",
    /**
     * Repository paths selected in the per-tab root display filter.
     * Empty means all loaded repos are shown (no filter active).
     * Distinct from [selectedRepoPaths] which controls which repos are *loaded*;
     * this field controls which of the loaded repos are *displayed*.
     */
    var selectedRootPaths: MutableList<String> = mutableListOf(),
    /** Whether the multi-repo root gutter shows repo name + color (true) or just a thin colored strip (false). */
    var rootGutterExpanded: Boolean = true
) {
    /**
     * Returns the subset of [allRepos] that this config selects.
     *
     * If [selectedRepoPaths] is empty, all repos are returned (the "all" default).
     * Stale paths that no longer correspond to an active repo are silently ignored.
     */
    fun selectedRepos(allRepos: Collection<JujutsuRepository>): List<JujutsuRepository> {
        if (selectedRepoPaths.isEmpty()) return allRepos.toList()
        val pathSet = selectedRepoPaths.toSet()
        return allRepos.filter { it.directory.path in pathSet }
    }
}
