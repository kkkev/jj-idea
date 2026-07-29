package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.ColoredTableCellRenderer
import `in`.kkkev.jjidea.ui.log.JujutsuLogTable
import `in`.kkkev.jjidea.ui.log.JujutsuLogTableModel
import javax.swing.JTable

abstract class TextTableCellRenderer<T> : ColoredTableCellRenderer() {
    val canvas = object : StyledTextCanvas() {
        override fun append(text: String) {
            this@TextTableCellRenderer.append(text, style)
        }
    }

    protected var isWorkingCopyRow = false

    /**
     * True when this exact (row, column) cell is the one [JujutsuLogTable.hoveredLinkRow]/
     * [JujutsuLogTable.hoveredLinkCol] point at — i.e. the pointer is over this cell's clickable
     * link. Renderers that contain a [TextCanvas.linked] element (e.g. `UserCellRenderer`'s
     * mailto link) use this to apply [TextCanvas.underlined] only while hovered, matching the
     * "colored always, underlined on hover" link convention (jj-idea-iesq).
     */
    protected var isHoveredLinkCell = false

    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ) {
        // Check if this row is the working copy
        val model = table.model as? JujutsuLogTableModel
        isWorkingCopyRow = model?.getEntry(row)?.isWorkingCopy ?: false

        val hoverTable = table as? JujutsuLogTable
        isHoveredLinkCell =
            hoverTable != null &&
            hoverTable.hoveredLinkRow == row &&
            hoverTable.hoveredLinkCol == column

        @Suppress("UNCHECKED_CAST")
        (value as? T)?.let { render(it) }
    }

    abstract fun render(value: T)
}
