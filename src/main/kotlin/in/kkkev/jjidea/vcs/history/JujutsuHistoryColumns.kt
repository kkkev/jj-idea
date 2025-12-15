package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColumnInfo
import java.util.*
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Custom column for committer (may differ from author in JJ)
 */
class CommitterColumnInfo : ColumnInfo<VcsFileRevision, String>("Committer") {
    override fun valueOf(revision: VcsFileRevision): String =
        (revision as? JujutsuFileRevision)?.getCommitter() ?: ""

    override fun getComparator(): Comparator<VcsFileRevision> =
        compareBy { (it as? JujutsuFileRevision)?.getCommitter() ?: "" }

    override fun getPreferredStringValue() = "user@example.com"

    override fun getAdditionalWidth() = 10
}

/**
 * Custom column for commit timestamp (committer timestamp)
 */
class CommitTimestampColumnInfo : ColumnInfo<VcsFileRevision, Date?>("Commit Time") {
    override fun valueOf(revision: VcsFileRevision): Date? =
        (revision as? JujutsuFileRevision)?.getCommitterDate()

    override fun getComparator(): Comparator<VcsFileRevision> =
        compareBy(nullsLast()) { (it as? JujutsuFileRevision)?.getCommitterDate() }

    override fun getRenderer(revision: VcsFileRevision): TableCellRenderer {
        return object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ) {
                if (value is Date) {
                    append(DateFormatUtil.formatPrettyDateTime(value), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }
    }

    override fun getPreferredStringValue() = "2025-12-15 12:00"

    override fun getAdditionalWidth() = 10
}
