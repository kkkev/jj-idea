package `in`.kkkev.jjidea.ui.components

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/**
 * Regression test: [ColoredTableCellRenderer.clear()][com.intellij.ui.ColoredTableCellRenderer]
 * (called by the platform before [TextTableCellRenderer.customizeCellRenderer] runs) resets
 * fragments/icon but not `toolTipText` - so, before this fix, a `null` cell value (e.g. a pending
 * log entry's null author/date - see `PendingLogEntryRenderingTest`) left whatever the
 * *previous* row's `render()` had set. [Stub] stands in for `UserCellRenderer`/`DateCellRenderer`,
 * which both set `toolTipText` from their value.
 */
class TextTableCellRendererTooltipTest {
    private class Stub : TextTableCellRenderer<String>() {
        override fun render(value: String) {
            canvas.append(value)
            toolTipText = "tooltip for $value"
        }
    }

    @Test
    fun `a null value after a real one does not keep the previous row's tooltip`() {
        val table = JTable(DefaultTableModel(2, 1))
        val renderer = Stub()

        renderer.getTableCellRendererComponent(table, "row-0", false, false, 0, 0)
        renderer.toolTipText shouldBe "tooltip for row-0"

        renderer.getTableCellRendererComponent(table, null, false, false, 1, 0)

        renderer.toolTipText.shouldBeNull()
    }
}
