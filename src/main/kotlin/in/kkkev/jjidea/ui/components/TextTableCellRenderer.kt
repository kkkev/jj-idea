package `in`.kkkev.jjidea.ui.components

import com.intellij.ui.ColoredTableCellRenderer
import `in`.kkkev.jjidea.ui.log.JujutsuLogTableModel
import javax.swing.JTable

abstract class TextTableCellRenderer<T> : ColoredTableCellRenderer() {
    val canvas = object : StyledTextCanvas() {
        override fun append(text: String) {
            this@TextTableCellRenderer.append(text, style)
        }
    }

    protected var isWorkingCopyRow = false

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

        @Suppress("UNCHECKED_CAST")
        (value as? T)?.let { render(it) }
    }

    abstract fun render(value: T)
}
