package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColumnInfo
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber
import java.util.*
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Custom column for Change ID with formatted display (bold short part)
 */
class ChangeIdColumnInfo : ColumnInfo<VcsFileRevision, String>("Change ID") {

    private val renderer = object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ) {
            if (value !is String) return

            // Get the revision to extract formatted change ID
            val tableModel = table.model
            if (row < 0 || row >= tableModel.rowCount) return

            // Try to get the actual revision object to access the revision number
            val actualRow = table.convertRowIndexToModel(row)
            val revisionColumn = 0  // Revision is typically first column

            val cellValue = tableModel.getValueAt(actualRow, revisionColumn)
            val revisionNumber = when (cellValue) {
                is VcsFileRevision -> cellValue.revisionNumber
                is JujutsuRevisionNumber -> cellValue
                else -> null
            } as? JujutsuRevisionNumber

            if (revisionNumber != null) {
                // Get short and full versions
                val shortVersion = revisionNumber.toShortString()
                val fullVersion = revisionNumber.asString()

                // Render short part in bold, remainder in gray
                append(shortVersion, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                if (fullVersion.length > shortVersion.length) {
                    val remainder = fullVersion.substring(shortVersion.length)
                    append(remainder, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            } else {
                // Fallback to plain rendering
                append(value, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }

    override fun valueOf(revision: VcsFileRevision): String =
        revision.revisionNumber.asString()

    override fun getRenderer(revision: VcsFileRevision): TableCellRenderer = renderer

    override fun getComparator(): Comparator<VcsFileRevision> =
        compareBy { it.revisionNumber.asString() }

    // Set preferred column width (in pixels)
    override fun getWidth(table: JTable): Int = 180

    override fun getMaxStringValue(): String = "k".repeat(24) // Approximate max width
}
