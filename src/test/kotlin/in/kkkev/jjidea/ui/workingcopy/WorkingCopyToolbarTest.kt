package `in`.kkkev.jjidea.ui.workingcopy

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * Guards jj-idea-xsa8 (GitHub #61): the Working Copy toolbar offers "actions anchored at `@`" —
 * New Change, Split, Squash, Abandon, Create Bookmark, Advance Bookmark, Set Tag — with stable
 * action instances across refreshes (`ActionToolbarImpl` reuses buttons by identity) and correct
 * enablement even before/after a repository is bound.
 *
 * Platform-tagged because constructing [WorkingCopyControlsPanel] needs IJPGP's full platform
 * classpath, same as the sibling [WorkingCopyControlsPanelEnterKeyTest].
 */
@Tag("platform")
@TestApplication
@RunInEdt
class WorkingCopyToolbarTest {
    private val project = projectFixture()

    private fun updated(action: AnAction): Presentation {
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        return event.presentation
    }

    @Test
    fun `toolbar offers New Change, Split, Squash, Abandon, Create Bookmark, Advance Bookmark, Set Tag, in order`() {
        val panel = WorkingCopyControlsPanel(project.get())
        val actions = panel.createActionToolbar(JPanel()).actionGroup.getChildren(null)

        actions.map { if (it is Separator) null else updated(it).text } shouldBe listOf(
            JujutsuBundle.message("button.newchange"),
            JujutsuBundle.message("log.action.split"),
            JujutsuBundle.message("log.action.squash.into.parent"),
            JujutsuBundle.message("log.action.abandon"),
            null, // separator
            JujutsuBundle.message("action.bookmark.create"),
            JujutsuBundle.message("action.bookmark.advance.closest"),
            JujutsuBundle.message("action.tag.set")
        )
    }

    @Test
    fun `the group updates on EDT`() {
        val panel = WorkingCopyControlsPanel(project.get())
        panel.createActionToolbar(JPanel()).actionGroup.getActionUpdateThread() shouldBe ActionUpdateThread.EDT
    }

    @Test
    fun `the same action instances are returned across repeated getChildren() calls`() {
        // ActionToolbarImpl reuses each button's JComponent by action identity.
        val panel = WorkingCopyControlsPanel(project.get())
        val group = panel.createActionToolbar(JPanel()).actionGroup

        val first = group.getChildren(null)
        val second = group.getChildren(null)

        first.size shouldBe second.size
        first.indices.forEach { i -> second[i] shouldBeSameInstanceAs first[i] }
    }

    @Test
    fun `with no bound repository Advance Bookmark Here is hidden, not just disabled`() {
        val panel = WorkingCopyControlsPanel(project.get())
        val advanceAction = panel.createActionToolbar(JPanel()).actionGroup.getChildren(null)
            .single { updated(it).text == JujutsuBundle.message("action.bookmark.advance.closest") }

        updated(advanceAction).isVisible shouldBe false
    }

    @Test
    fun `binding a repository makes the SAME Advance action instance show Advance Bookmark Here`() {
        val panel = WorkingCopyControlsPanel(project.get())
        val advanceAction = panel.createActionToolbar(JPanel()).actionGroup.getChildren(null)
            .single { updated(it).text == JujutsuBundle.message("action.bookmark.advance.closest") }

        updated(advanceAction).isVisible shouldBe false // before binding

        val testProject = project.get()
        val repo = mockk<JujutsuRepository>(relaxed = true) {
            every { project } returns testProject
        }
        every { repo.workingCopy } returns LogEntry(repo, ChangeId("abc"), CommitId("abc"), "")
        panel.boundRepository = repo

        updated(advanceAction).isVisible shouldBe true // same instance, after binding
    }

    @Test
    fun `Split starts disabled with no bound repository, not accidentally always-enabled`() {
        val panel = WorkingCopyControlsPanel(project.get())
        val splitAction = panel.createActionToolbar(JPanel()).actionGroup.getChildren(null)
            .single { updated(it).text == JujutsuBundle.message("log.action.split") }

        updated(splitAction).isEnabled shouldBe false
    }

    @Test
    fun `createTopBar includes the repo selector alongside the action toolbar`() {
        val panel = WorkingCopyControlsPanel(project.get())
        val topBar = panel.createTopBar(JPanel())

        UIUtil.findComponentOfType(topBar, JComboBox::class.java) shouldNotBe null
    }
}
