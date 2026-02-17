package `in`.kkkev.jjidea.ui.log

/**
 * Manages column visibility and content for the Jujutsu log table.
 *
 * Controls:
 * 1. Which table columns are visible
 * 2. What elements are shown in the combined graph column (when not using separate columns)
 */
class JujutsuColumnManager {
    // Root gutter column (visible when multiple roots)
    var showRootGutterColumn: Boolean = false

    // Graph column (visible in log view, hidden in file history)
    var showGraphColumn: Boolean = true

    // Separate columns (mutually exclusive with showing in graph column)
    var showStatusColumn: Boolean = false
    var showChangeIdColumn: Boolean = false
    var showDescriptionColumn: Boolean = false
    var showDecorationsColumn: Boolean = false

    // Standard columns
    var showAuthorColumn: Boolean = true
    var showCommitterColumn: Boolean = false
    var showDateColumn: Boolean = true

    // Computed properties for renderer
    val showChangeId: Boolean get() = !showChangeIdColumn
    val showDescription: Boolean get() = !showDescriptionColumn
    val showDecorations: Boolean get() = !showDecorationsColumn

    /**
     * Get list of visible columns (indices).
     * Graph column is visible when showGraphColumn is true (hidden in file history).
     * Root gutter is visible when showRootGutterColumn is true.
     */
    fun getVisibleColumns(): List<Int> {
        val columns = mutableListOf<Int>()

        if (showRootGutterColumn) {
            columns.add(JujutsuLogTableModel.COLUMN_ROOT_GUTTER)
        }

        if (showGraphColumn) {
            columns.add(JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION)
        }

        if (showStatusColumn) {
            columns.add(JujutsuLogTableModel.COLUMN_STATUS)
        }

        if (showChangeIdColumn) {
            columns.add(JujutsuLogTableModel.COLUMN_ID)
        }

        if (showDescriptionColumn) {
            columns.add(JujutsuLogTableModel.COLUMN_DESCRIPTION)
        }

        if (showDecorationsColumn) {
            columns.add(JujutsuLogTableModel.COLUMN_DECORATIONS)
        }

        if (showAuthorColumn) {
            columns.add(JujutsuLogTableModel.COLUMN_AUTHOR)
        }

        if (showCommitterColumn) {
            columns.add(JujutsuLogTableModel.COLUMN_COMMITTER)
        }

        if (showDateColumn) {
            columns.add(JujutsuLogTableModel.COLUMN_DATE)
        }

        return columns
    }

    /**
     * Check if a column should be visible.
     */
    fun isColumnVisible(columnIndex: Int): Boolean = when (columnIndex) {
        JujutsuLogTableModel.COLUMN_ROOT_GUTTER -> showRootGutterColumn
        JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION -> showGraphColumn
        JujutsuLogTableModel.COLUMN_STATUS -> showStatusColumn
        JujutsuLogTableModel.COLUMN_ID -> showChangeIdColumn
        JujutsuLogTableModel.COLUMN_DESCRIPTION -> showDescriptionColumn
        JujutsuLogTableModel.COLUMN_DECORATIONS -> showDecorationsColumn
        JujutsuLogTableModel.COLUMN_AUTHOR -> showAuthorColumn
        JujutsuLogTableModel.COLUMN_COMMITTER -> showCommitterColumn
        JujutsuLogTableModel.COLUMN_DATE -> showDateColumn
        else -> false
    }

    companion object {
        /**
         * Default instance with all columns and elements visible.
         */
        val DEFAULT = JujutsuColumnManager()
    }
}
