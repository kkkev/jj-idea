package `in`.kkkev.jjidea.ui.log

class JujutsuColumnManager {
    var showRootGutterColumn: Boolean = false
    var showAuthorColumn: Boolean = true
    var showCommitterColumn: Boolean = false
    var showDateColumn: Boolean = true

    var showStatus: Boolean = true
    var showChangeId: Boolean = true
    var showDescription: Boolean = true
    var showDecorations: Boolean = true

    // Whether the graph+description column flexes to fill the window width, squeezing
    // author/committer/date before falling back to horizontal scroll (jj-idea-lzq7).
    var fitColumnsToWidth: Boolean = true

    fun getVisibleColumns(): List<Int> = buildList {
        if (showRootGutterColumn) add(JujutsuLogTableModel.COLUMN_ROOT_GUTTER)
        add(JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION)
        if (showAuthorColumn) add(JujutsuLogTableModel.COLUMN_AUTHOR)
        if (showCommitterColumn) add(JujutsuLogTableModel.COLUMN_COMMITTER)
        if (showDateColumn) add(JujutsuLogTableModel.COLUMN_DATE)
    }

    fun isColumnVisible(columnIndex: Int) = when (columnIndex) {
        JujutsuLogTableModel.COLUMN_ROOT_GUTTER -> showRootGutterColumn
        JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION -> true
        JujutsuLogTableModel.COLUMN_AUTHOR -> showAuthorColumn
        JujutsuLogTableModel.COLUMN_COMMITTER -> showCommitterColumn
        JujutsuLogTableModel.COLUMN_DATE -> showDateColumn
        else -> false
    }

    /** Restore visibility state from a persisted [config]. Does not touch [showRootGutterColumn] (always dynamic). */
    fun loadFrom(config: `in`.kkkev.jjidea.settings.LogWindowConfig) {
        showAuthorColumn = config.showAuthorColumn
        showCommitterColumn = config.showCommitterColumn
        showDateColumn = config.showDateColumn
        showStatus = config.showStatus
        showChangeId = config.showChangeId
        showDescription = config.showDescription
        showDecorations = config.showDecorations

        if (!config.fitColumnsToWidthResolved) {
            // First load after upgrade / first run for this tab: a non-empty columnWidths map
            // means the user has explicitly dragged a column (saveColumnWidths only writes on
            // user resize - see JujutsuLogTable.columnMarginChanged), so preserve their manual
            // layout by leaving fit-to-width off; otherwise default to responsive (jj-idea-lzq7).
            // Resolved once and frozen so a later drag can't silently flip the mode back off.
            config.fitColumnsToWidth = config.columnWidths.isEmpty()
            config.fitColumnsToWidthResolved = true
        }
        fitColumnsToWidth = config.fitColumnsToWidth
    }

    /** Persist current visibility state into [config]. Does not include [showRootGutterColumn] (dynamic). */
    fun saveTo(config: `in`.kkkev.jjidea.settings.LogWindowConfig) {
        config.showAuthorColumn = showAuthorColumn
        config.showCommitterColumn = showCommitterColumn
        config.showDateColumn = showDateColumn
        config.showStatus = showStatus
        config.showChangeId = showChangeId
        config.showDescription = showDescription
        config.showDecorations = showDecorations
        config.fitColumnsToWidth = fitColumnsToWidth
        config.fitColumnsToWidthResolved = true
    }

    companion object {
        val DEFAULT = JujutsuColumnManager()
    }
}
