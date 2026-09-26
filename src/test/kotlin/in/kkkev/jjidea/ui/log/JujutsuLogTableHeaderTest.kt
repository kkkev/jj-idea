package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag as JupiterTag

/**
 * Coverage for jj-idea-rozx (GitHub #78): the log table's header is now a zero-height
 * `JBTable.InvisibleResizableHeader`, not a visible always-blank row. It's a protected nested class
 * of `JBTable`, so tests here check it via runtime class name and reflection rather than by type.
 * Gesture-level behaviour (an actual drag) needs a real windowing system - see
 * docs/manual-tests.md's MT-LOG-TABLE "Column management".
 */
@JupiterTag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableHeaderTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun newTable(): JujutsuLogTable {
        val table = JujutsuLogTable(project.get())
        Disposer.register(project.get(), table)
        return table
    }

    // canMoveOrResizeColumn refuses everything while the table has zero rows.
    private fun newTableWithOneRow(): JujutsuLogTable {
        val table = newTable()
        table.setEntries(
            listOf(
                LogEntry(
                    repo = repo,
                    id = ChangeId("a", "a", null),
                    commitId = CommitId("commit-a"),
                    underlyingDescription = "desc a"
                )
            )
        )
        return table
    }

    @Test
    fun `the table header is the invisible git-parity header, not a visible labeled row`() {
        val table = newTable()

        table.tableHeader.javaClass.superclass.simpleName shouldContain "InvisibleResizableHeader"
        table.tableHeader.preferredSize.height shouldBe 0
    }

    @Test
    fun `resizing is still allowed so columns can be resized by dragging in the body`() {
        val table = newTable()

        table.tableHeader.resizingAllowed shouldBe true
    }

    @Test
    fun `the root gutter column cannot be moved or resized, unlike the other columns`() {
        val table = newTableWithOneRow()

        val canMoveOrResize = table.tableHeader.javaClass
            .getDeclaredMethod("canMoveOrResizeColumn", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }

        canMoveOrResize.invoke(table.tableHeader, JujutsuLogTableModel.COLUMN_ROOT_GUTTER) shouldBe false
        canMoveOrResize.invoke(table.tableHeader, JujutsuLogTableModel.COLUMN_AUTHOR) shouldBe true
        canMoveOrResize.invoke(table.tableHeader, JujutsuLogTableModel.COLUMN_DATE) shouldBe true
    }

    @Test
    fun `the trailing spacer column cannot be moved or resized either`() {
        val table = newTableWithOneRow()

        val canMoveOrResize = table.tableHeader.javaClass
            .getDeclaredMethod("canMoveOrResizeColumn", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }

        canMoveOrResize.invoke(table.tableHeader, JujutsuLogTableModel.COLUMN_TRAILING_SPACER) shouldBe false
    }
}
