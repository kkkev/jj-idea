package `in`.kkkev.jjidea.ui.common

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.components.JBScrollPane
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.mockRepo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * jj-idea-1uz0 (GitHub #125): [createSourcePanel] was unbounded, so squashing many commits grew
 * the dialog's top section until the destination picker below it had zero height.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class SourcePanelSizeTest {
    private val project = projectFixture()

    private fun entries(n: Int): List<LogEntry> {
        val repo = mockRepo(project.get())
        return (1..n).map {
            LogEntry(
                repo = repo,
                id = ChangeId("id$it", "id$it"),
                commitId = CommitId("c$it", "c$it"),
                underlyingDescription = "commit $it"
            )
        }
    }

    private fun layoutTree(c: java.awt.Component) {
        if (c is Container) {
            c.doLayout()
            c.components.forEach(::layoutTree)
        }
    }

    @Test
    fun `a short list is the bare pane, unchanged`() {
        val panel = createSourcePanel(project.get(), entries(MAX_VISIBLE_SOURCE_ROWS))

        (panel is JScrollPane) shouldBe false
    }

    @Test
    fun `a long list scrolls within a capped height`() {
        val long = entries(50)
        val panel = createSourcePanel(project.get(), long)

        panel.shouldBeInstanceOf<JBScrollPane>()
        val singleRow = createSourcePanel(project.get(), entries(1)).preferredSize.height
        panel.preferredSize.height shouldBeLessThan singleRow * (MAX_VISIBLE_SOURCE_ROWS + 2)
        panel.maximumSize.height shouldBeLessThan singleRow * (MAX_VISIBLE_SOURCE_ROWS + 2)
    }

    /**
     * The dialogs' shape (SquashIntoDialog/RebaseDialog/DuplicateDialog): the source panel sits in a
     * `BoxLayout` top section at `BorderLayout.NORTH`, with the picker as CENTER. Built directly
     * rather than through a real dialog, which would also pull in the picker table and preview.
     */
    private fun dialogShapedLayout(sourceCount: Int): JPanel {
        val picker = JPanel()
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(createSourcePanel(project.get(), entries(sourceCount)))
        }
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(picker, BorderLayout.CENTER)
            size = Dimension(1150, 650)
            layoutTree(this)
        }
    }

    @Test
    fun `many sources leave the picker below them a visible height`() {
        val picker = dialogShapedLayout(50).getComponent(1)

        picker.height shouldBeGreaterThan 0
    }
}
