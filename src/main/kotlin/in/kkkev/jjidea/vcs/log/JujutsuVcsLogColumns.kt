package `in`.kkkev.jjidea.vcs.log

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.vcs.log.ui.table.GraphTableModel
import com.intellij.vcs.log.ui.table.VcsLogGraphTable
import com.intellij.vcs.log.ui.table.column.VcsLogCustomColumn
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.JujutsuCommitMetadataBase
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Custom Change ID column for Jujutsu VCS Log
 */
class JujutsuChangeIdColumn : VcsLogCustomColumn<ChangeId> {

    override val id: String = "Jujutsu.ChangeId"

    override val localizedName: String = "Change ID"

    override val isDynamic: Boolean = true

    override fun getValue(model: GraphTableModel, row: Int): ChangeId? {
        val metadata = model.getCommitMetadata(row) as? JujutsuCommitMetadataBase ?: return null
        return metadata.entry.changeId
    }

    override fun createTableCellRenderer(table: VcsLogGraphTable): TableCellRenderer {
        return object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ) {
                if (value is ChangeId) {
                    // Show JJ's dynamic short prefix in bold
                    append(value.short, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    // Show remainder up to display limit in gray/small
                    if (value.displayRemainder.isNotEmpty()) {
                        append(value.displayRemainder, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
            }
        }
    }

    override fun getStubValue(model: GraphTableModel): ChangeId = ChangeId("qpvuntsm")

    override fun isEnabledByDefault(): Boolean = true

    override fun isAvailable(project: Project, roots: Collection<VirtualFile>): Boolean {
        // Only available if at least one root is a Jujutsu repository
        return roots.any { root ->
            ProjectLevelVcsManager.getInstance(project).getVcsFor(root) is JujutsuVcs
        }
    }
}

/**
 * Custom Bookmarks column for Jujutsu VCS Log
 */
class JujutsuBookmarksColumn : VcsLogCustomColumn<String> {

    override val id: String = "Jujutsu.Bookmarks"

    override val localizedName: String = "Bookmarks"

    override val isDynamic: Boolean = true

    override fun getValue(model: GraphTableModel, row: Int): String? {
        val metadata = model.getCommitMetadata(row) as? JujutsuCommitMetadataBase ?: return null
        val bookmarks = metadata.entry.bookmarks.joinToString(", ") { it.name }
        return if (bookmarks.isEmpty()) null else bookmarks
    }

    override fun createTableCellRenderer(table: VcsLogGraphTable): TableCellRenderer {
        return object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ) {
                if (value is String && value.isNotEmpty()) {
                    append(value, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }
    }

    override fun getStubValue(model: GraphTableModel): String = "main"

    override fun isEnabledByDefault(): Boolean = true

    override fun isAvailable(project: Project, roots: Collection<VirtualFile>): Boolean {
        return roots.any { root ->
            ProjectLevelVcsManager.getInstance(project).getVcsFor(root) is JujutsuVcs
        }
    }
}

/**
 * Custom Committer column for Jujutsu VCS Log (hidden by default)
 */
class JujutsuCommitterColumn : VcsLogCustomColumn<String> {

    override val id: String = "Jujutsu.Committer"

    override val localizedName: String = "Committer"

    override val isDynamic: Boolean = true

    override fun getValue(model: GraphTableModel, row: Int): String? {
        val metadata = model.getCommitMetadata(row) as? JujutsuCommitMetadataBase ?: return null
        return metadata.entry.committer?.name ?: metadata.entry.author?.name
    }

    override fun createTableCellRenderer(table: VcsLogGraphTable): TableCellRenderer {
        return object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ) {
                if (value is String) {
                    append(value, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }
    }

    override fun getStubValue(model: GraphTableModel): String = "John Doe"

    override fun isEnabledByDefault(): Boolean = false  // Hidden by default

    override fun isAvailable(project: Project, roots: Collection<VirtualFile>): Boolean {
        return roots.any { root ->
            ProjectLevelVcsManager.getInstance(project).getVcsFor(root) is JujutsuVcs
        }
    }
}
