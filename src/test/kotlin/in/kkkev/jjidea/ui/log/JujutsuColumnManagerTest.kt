package `in`.kkkev.jjidea.ui.log

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JujutsuColumnManagerTest {
    @Test
    fun `default manager shows combined mode`() {
        val manager = JujutsuColumnManager()

        // Separate columns hidden by default
        manager.showChangeIdColumn shouldBe false
        manager.showDescriptionColumn shouldBe false
        manager.showDecorationsColumn shouldBe false

        // Standard columns visible
        manager.showAuthorColumn shouldBe true
        manager.showDateColumn shouldBe true

        // Elements shown in graph when separate columns hidden
        manager.showChangeId shouldBe true
        manager.showDescription shouldBe true
        manager.showDecorations shouldBe true
    }

    @Test
    fun `graph column is always visible`() {
        val manager = JujutsuColumnManager()

        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION) shouldBe true
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
    fun `getVisibleColumns includes all columns by default`() {
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
    fun `separate change id column hides it from graph`() {
        val manager = JujutsuColumnManager()

        // Initially shown in graph
        manager.showChangeId shouldBe true

        // Enable separate column
        manager.showChangeIdColumn = true

        // No longer shown in graph
        manager.showChangeId shouldBe false
    }

    @Test
    fun `separate description column hides it from graph`() {
        val manager = JujutsuColumnManager()

        // Initially shown in graph
        manager.showDescription shouldBe true

        // Enable separate column
        manager.showDescriptionColumn = true

        // No longer shown in graph
        manager.showDescription shouldBe false
    }

    @Test
    fun `separate decorations column hides it from graph`() {
        val manager = JujutsuColumnManager()

        // Initially shown in graph
        manager.showDecorations shouldBe true

        // Enable separate column
        manager.showDecorationsColumn = true

        // No longer shown in graph
        manager.showDecorations shouldBe false
    }

    @Test
    fun `getVisibleColumns includes separate columns when enabled`() {
        val manager = JujutsuColumnManager()
        manager.showChangeIdColumn = true
        manager.showDescriptionColumn = true
        manager.showDecorationsColumn = true

        val visible = manager.getVisibleColumns()

        visible shouldContain JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION
        visible shouldContain JujutsuLogTableModel.COLUMN_CHANGE_ID
        visible shouldContain JujutsuLogTableModel.COLUMN_DESCRIPTION
        visible shouldContain JujutsuLogTableModel.COLUMN_DECORATIONS
        visible shouldContain JujutsuLogTableModel.COLUMN_AUTHOR
        visible shouldContain JujutsuLogTableModel.COLUMN_DATE
    }

    @Test
    fun `default instance has all elements visible`() {
        val manager = JujutsuColumnManager.DEFAULT

        manager.showAuthorColumn shouldBe true
        manager.showDateColumn shouldBe true
        manager.showChangeId shouldBe true
        manager.showDescription shouldBe true
        manager.showDecorations shouldBe true
    }
}
