package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag as JupiterTag

/**
 * Tests for [JujutsuLogContextMenuActions.createActionGroup]'s `liveSelection` parameter
 * (jj-idea-crt0): when the passed-in [LogEntry] list genuinely reflects the log table's current
 * selection (the default, `liveSelection = true` - the log-row right-click path,
 * `JujutsuLogTable.showContextMenu`), Rebase/Describe must be added as the SAME registered
 * `Jujutsu.RebaseChangeToolbar`/`Jujutsu.DescribeChangeToolbar` instances the toolbar uses, so
 * IntelliJ can show their keyboard shortcut hint next to the menu item - a freshly-built anonymous
 * action per menu-open can never do that. When `liveSelection = false` (the change-id-link menu,
 * `clickActionGroup`'s `ChangeNavigationClick` branch, whose entry is a synthesized target that
 * isn't necessarily the table's live selection), those registered actions must NOT be used, since
 * they'd read the table's actual selection instead of the synthesized target.
 */
@JupiterTag("platform")
@TestApplication
@RunInEdt
class JujutsuLogContextMenuActionsLiveSelectionTest {
    private val projectFixture = projectFixture()
    private val project: Project get() = projectFixture.get()
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(immutable: Boolean = false) = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Test commit",
        immutable = immutable
    )

    private fun actionIds(entries: List<LogEntry>, liveSelection: Boolean): List<String?> {
        val group = JujutsuLogContextMenuActions.createActionGroup(project, entries, liveSelection)
        val actionManager = ActionManager.getInstance()
        return group.getChildren(null).map { actionManager.getId(it) }
    }

    @Test
    fun `live selection reuses the registered Rebase and Describe toolbar actions`() {
        val ids = actionIds(listOf(entry()), liveSelection = true)

        (ids.contains("Jujutsu.RebaseChangeToolbar")) shouldBe true
        (ids.contains("Jujutsu.DescribeChangeToolbar")) shouldBe true
    }

    @Test
    fun `non-live selection (change-id link menu) does not use the registered toolbar actions`() {
        val ids = actionIds(listOf(entry()), liveSelection = false)

        (ids.contains("Jujutsu.RebaseChangeToolbar")) shouldBe false
        (ids.contains("Jujutsu.DescribeChangeToolbar")) shouldBe false
    }

    @Test
    fun `non-live selection omits New and Edit entirely (no fixed-target equivalent)`() {
        val ids = actionIds(listOf(entry()), liveSelection = false)

        (ids.contains("Jujutsu.NewChange")) shouldBe false
        (ids.contains("Jujutsu.EditChange")) shouldBe false
    }

    @Test
    fun `live selection includes New and Edit by their registered ids`() {
        val ids = actionIds(listOf(entry()), liveSelection = true)

        (ids.contains("Jujutsu.NewChange")) shouldBe true
        (ids.contains("Jujutsu.EditChange")) shouldBe true
    }
}
