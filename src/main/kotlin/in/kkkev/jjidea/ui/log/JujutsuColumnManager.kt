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

    // Separate columns (mutually exclusive with showing in graph column)
    var showStatusColumn: Boolean = false
    var showChangeIdColumn: Boolean = false
    var showDescriptionColumn: Boolean = false
    var showDecorationsColumn: Boolean = false

    // Standard columns
    var showAuthorColumn: Boolean = true
    var showCommitterColumn: Boolean = false
    var showDateColumn: Boolean = true

    // Graph column content elements (only shown if corresponding column is hidden)
    var showChangeIdInGraph: Boolean = true
    var showDescriptionInGraph: Boolean = true
    var showDecorationsInGraph: Boolean = true // Bookmarks, tags, working copy indicator

    // Computed properties for renderer
    val showChangeId: Boolean get() = !showChangeIdColumn && showChangeIdInGraph
    val showDescription: Boolean get() = !showDescriptionColumn && showDescriptionInGraph
    val showDecorations: Boolean get() = !showDecorationsColumn && showDecorationsInGraph

    /**
     * Get list of visible columns (indices).
     * Graph column is always visible. Root gutter is visible when showRootGutterColumn is true.
     */
    fun getVisibleColumns(): List<Int> {
        val columns = mutableListOf<Int>()

        if (showRootGutterColumn) {
            columns.add(JujutsuLogTableModel.COLUMN_ROOT_GUTTER)
        }

        columns.add(JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION)

        if (showStatusColumn) {
            columns.add(JujutsuLogTableModel.COLUMN_STATUS)
        }

        if (showChangeIdColumn) {
            columns.add(JujutsuLogTableModel.COLUMN_CHANGE_ID)
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
        JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION -> true // Always visible
        JujutsuLogTableModel.COLUMN_STATUS -> showStatusColumn
        JujutsuLogTableModel.COLUMN_CHANGE_ID -> showChangeIdColumn
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
