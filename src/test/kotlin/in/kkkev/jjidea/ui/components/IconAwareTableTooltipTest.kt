package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.project.Project
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.awt.Point
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Regression tests for jj-idea-2md7: dialog log-preview tables (Rebase/Squash/Duplicate pickers
 * and previews) rendered row tooltips via a plain Swing tooltip, which doesn't understand the
 * `icon:`/`unbreakable:` `<img>` markup [in.kkkev.jjidea.ui.log.JujutsuGraphAndDescriptionRenderer]
 * puts in `toolTipText` - producing a broken-image glyph. [installIconAwareTableTooltip] routes
 * those tooltips through [IconAwareHtmlPane] instead, the same fix already applied to the log
 * table and revision picker (jj-idea-fmrj).
 *
 * Plain unit tests (no platform classpath), mirroring [IconAwareTooltipBehaviourTest]'s approach:
 * a fake [TooltipHost] and a mocked [Project] (only dereferenced on the non-exercised show path).
 */
class IconAwareTableTooltipTest {
    private val project = mockk<Project>()

    /** A 2x1 table whose sole cell renderer reports [cellToolTip] as its `toolTipText`. */
    private fun tableWithTooltip(cellToolTip: String?): JTable {
        val model = object : AbstractTableModel() {
            override fun getRowCount() = 1
            override fun getColumnCount() = 1
            override fun getValueAt(row: Int, col: Int): Any = ""
        }
        val renderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ) = JLabel().apply { toolTipText = cellToolTip }
        }
        return JTable(model).apply {
            setDefaultRenderer(Any::class.java, renderer)
            setBounds(0, 0, 100, 100)
            doLayout()
        }
    }

    @Test
    fun `tableCellTooltipHtml returns the cell renderer's tooltip text`() {
        val table = tableWithTooltip("<html>tip</html>")

        tableCellTooltipHtml(table, Point(5, 5)) shouldBe "<html>tip</html>"
    }

    @Test
    fun `tableCellTooltipHtml is null off any row or column`() {
        val table = tableWithTooltip("<html>tip</html>")

        tableCellTooltipHtml(table, Point(5, 500)).shouldBeNull()
    }

    @Test
    fun `tableCellTooltipHtml is null when the cell renderer sets no tooltip`() {
        val table = tableWithTooltip(null)

        tableCellTooltipHtml(table, Point(5, 5)).shouldBeNull()
    }

    private class FakeHost : TooltipHost {
        var installed: com.intellij.ide.IdeTooltip? = null

        override fun install(owner: javax.swing.JComponent, tooltip: com.intellij.ide.IdeTooltip) {
            installed = tooltip
        }

        override fun hideOnMouseMove(e: java.awt.event.MouseEvent) = true

        override fun hideNow(owner: javax.swing.JComponent) {}

        override fun showNow(tooltip: com.intellij.ide.IdeTooltip) {}
    }

    @Test
    fun `installIconAwareTableTooltip installs a hint tooltip and records it as a client property`() {
        val table = tableWithTooltip("<html>tip</html>")
        val host = FakeHost()

        val tooltip = installIconAwareTableTooltip(table, project, host = host)

        tooltip.isHint shouldBe true
        table.iconAwareTooltip() shouldBe tooltip
        host.installed shouldBe tooltip
    }
}
