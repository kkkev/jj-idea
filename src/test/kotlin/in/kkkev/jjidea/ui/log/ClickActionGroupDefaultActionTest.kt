package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag as JupiterTag

/**
 * Tests for [JujutsuLogContextMenuActions.clickActionGroup]'s ordering contract (jj-idea-iesq):
 * the first action in each menu must be marked [DefaultClickAction] and mirror that element's
 * left-click default, since `JujutsuLogTable`'s right-click popup pre-selects whichever action
 * satisfies `it is DefaultClickAction`. Also covers the "Filter Log to/by" checkmark, which reads
 * [in.kkkev.jjidea.jj.JujutsuStateModel.activeReferenceFilter]/`activeAuthorFilter` directly - a
 * single project-level source of truth rather than a value threaded in by the caller.
 *
 * Platform-tagged because [DefaultActionGroup.add] resolves `ActionManager.getInstance()`
 * internally (to assign each action a synthetic ID), which needs the IDE Application initialized;
 * the checkmark tests additionally need a real [Project] to back `project.stateModel`.
 */
@JupiterTag("platform")
@TestApplication
@RunInEdt
class ClickActionGroupDefaultActionTest {
    private val projectFixture = projectFixture()
    private val project: Project get() = projectFixture.get()
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry() = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Test commit"
    )

    private fun DefaultActionGroup.nonSeparatorChildren() =
        getChildren(null).filterNot { it is Separator }

    @Test
    fun `bookmark chip menu leads with a DefaultClickAction that filters to the reference`() {
        val target = BookmarkClick(repo, entry(), Bookmark("main"))

        val children = JujutsuLogContextMenuActions.clickActionGroup(project, target).nonSeparatorChildren()

        children.first().shouldBeInstanceOfDefaultClickAction()
    }

    @Test
    fun `tag chip menu leads with a DefaultClickAction that filters to the reference`() {
        val target = TagClick(repo, entry(), Tag("v1"))

        val children = JujutsuLogContextMenuActions.clickActionGroup(project, target).nonSeparatorChildren()

        children.first().shouldBeInstanceOfDefaultClickAction()
    }

    @Test
    fun `author menu leads with a DefaultClickAction and also offers filter by author`() {
        val target = PersonClick(repo, entry(), VcsUserImpl("Alice", "alice@example.com"), canFilter = true)

        val children = JujutsuLogContextMenuActions.clickActionGroup(project, target).nonSeparatorChildren()

        children.size shouldBe 2
        children.first().shouldBeInstanceOfDefaultClickAction()
    }

    @Test
    fun `committer menu has only the DefaultClickAction - no filter option`() {
        val target = PersonClick(repo, entry(), VcsUserImpl("Bob", "bob@example.com"), canFilter = false)

        val children = JujutsuLogContextMenuActions.clickActionGroup(project, target).nonSeparatorChildren()

        children.size shouldBe 1
        children.first().shouldBeInstanceOfDefaultClickAction()
    }

    private fun Any.shouldBeInstanceOfDefaultClickAction() {
        (this is DefaultClickAction) shouldBe true
    }

    private fun ToggleAction.isSelectedInTest() = isSelected(TestActionEvent.createTestEvent(this))

    @Test
    fun `bookmark chip's Filter action is checked when it is the active reference filter`() {
        project.stateModel.activeReferenceFilter = "main"
        val target = BookmarkClick(repo, entry(), Bookmark("main"))

        val checked = JujutsuLogContextMenuActions.clickActionGroup(project, target).nonSeparatorChildren().first()

        (checked as ToggleAction).isSelectedInTest() shouldBe true
    }

    @Test
    fun `bookmark chip's Filter action is unchecked when a different reference is active`() {
        project.stateModel.activeReferenceFilter = "other-bookmark"
        val target = BookmarkClick(repo, entry(), Bookmark("main"))

        val unchecked = JujutsuLogContextMenuActions.clickActionGroup(project, target).nonSeparatorChildren().first()

        (unchecked as ToggleAction).isSelectedInTest() shouldBe false
    }

    @Test
    fun `tag chip's Filter action is checked when it is the active reference filter`() {
        project.stateModel.activeReferenceFilter = "v1"
        val target = TagClick(repo, entry(), Tag("v1"))

        val checked = JujutsuLogContextMenuActions.clickActionGroup(project, target).nonSeparatorChildren().first()

        (checked as ToggleAction).isSelectedInTest() shouldBe true
    }

    @Test
    fun `Filter by author action is checked when this author is the active author filter`() {
        project.stateModel.activeAuthorFilter = setOf("alice@example.com")
        val target = PersonClick(repo, entry(), VcsUserImpl("Alice", "alice@example.com"), canFilter = true)

        val children = JujutsuLogContextMenuActions.clickActionGroup(project, target).nonSeparatorChildren()

        (children[1] as ToggleAction).isSelectedInTest() shouldBe true
    }

    @Test
    fun `no active filter leaves every toggle action unchecked`() {
        project.stateModel.activeReferenceFilter = ""
        val target = BookmarkClick(repo, entry(), Bookmark("main"))

        val unchecked = JujutsuLogContextMenuActions.clickActionGroup(project, target).nonSeparatorChildren().first()

        (unchecked as ToggleAction).isSelectedInTest() shouldBe false
    }
}
