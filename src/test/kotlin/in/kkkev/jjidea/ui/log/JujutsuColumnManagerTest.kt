package `in`.kkkev.jjidea.ui.log

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JujutsuColumnManagerTest {
    @Test
    fun `default manager shows all elements`() {
        val manager = JujutsuColumnManager()

        manager.showStatus shouldBe true
        manager.showChangeId shouldBe true
        manager.showDescription shouldBe true
        manager.showDecorations shouldBe true

        manager.showAuthorColumn shouldBe true
        manager.showDateColumn shouldBe true
        manager.showCommitterColumn shouldBe false
        manager.showRootGutterColumn shouldBe false
    }

    @Test
    fun `combined column is always visible`() {
        val manager = JujutsuColumnManager()

        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION) shouldBe true
    }

    @Test
    fun `combined column always in getVisibleColumns`() {
        val manager = JujutsuColumnManager()

        manager.getVisibleColumns() shouldContain JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION
    }

    @Test
    fun `element toggles do not affect column list`() {
        val manager = JujutsuColumnManager()
        val baseColumns = manager.getVisibleColumns()

        manager.showStatus = false
        manager.showChangeId = false
        manager.showDescription = false
        manager.showDecorations = false

        manager.getVisibleColumns() shouldBe baseColumns
    }

    @Test
    fun `author column visibility can be toggled`() {
        val manager = JujutsuColumnManager()

        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_AUTHOR) shouldBe true

        manager.showAuthorColumn = false
        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_AUTHOR) shouldBe false
    }

    @Test
    fun `date column visibility can be toggled`() {
        val manager = JujutsuColumnManager()

        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_DATE) shouldBe true

        manager.showDateColumn = false
        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_DATE) shouldBe false
    }

    @Test
    fun `getVisibleColumns includes graph and standard columns by default`() {
        val manager = JujutsuColumnManager()

        val visible = manager.getVisibleColumns()

        visible shouldContain JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION
        visible shouldContain JujutsuLogTableModel.COLUMN_AUTHOR
        visible shouldContain JujutsuLogTableModel.COLUMN_DATE
    }

    @Test
    fun `getVisibleColumns excludes hidden author column`() {
        val manager = JujutsuColumnManager()
        manager.showAuthorColumn = false

        val visible = manager.getVisibleColumns()

        visible shouldContain JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION
        visible shouldNotContain JujutsuLogTableModel.COLUMN_AUTHOR
        visible shouldContain JujutsuLogTableModel.COLUMN_DATE
    }

    @Test
    fun `getVisibleColumns excludes hidden date column`() {
        val manager = JujutsuColumnManager()
        manager.showDateColumn = false

        val visible = manager.getVisibleColumns()

        visible shouldContain JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION
        visible shouldContain JujutsuLogTableModel.COLUMN_AUTHOR
        visible shouldNotContain JujutsuLogTableModel.COLUMN_DATE
    }

    @Test
    fun `root gutter column hidden by default`() {
        val manager = JujutsuColumnManager()

        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_ROOT_GUTTER) shouldBe false

        manager.showRootGutterColumn = true
        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_ROOT_GUTTER) shouldBe true
    }

    @Test
    fun `default instance has all elements and standard columns visible`() {
        val manager = JujutsuColumnManager.DEFAULT

        manager.showAuthorColumn shouldBe true
        manager.showDateColumn shouldBe true
        manager.showStatus shouldBe true
        manager.showChangeId shouldBe true
        manager.showDescription shouldBe true
        manager.showDecorations shouldBe true
    }
}
