package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.actions.id
import `in`.kkkev.jjidea.actions.withLogEntry
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag as JupiterTag

/**
 * Tests that [JujutsuLogContextMenuActions.createActionGroup] always uses the registered log actions (the SAME
 * instances the toolbar uses, so IntelliJ can show keyboard shortcut hints), and that [withLogEntry] is what lets
 * those actions act on a menu target that isn't the table's selection (a change-id link, jj-idea-eir1).
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

    private fun actionIds(entries: List<LogEntry>): List<String?> =
        JujutsuLogContextMenuActions.createActionGroup(project, entries).getChildren(null).map { it.id }

    @Test
    fun `menu uses the registered toolbar and keymappable actions`() {
        val ids = actionIds(listOf(entry()))

        listOf(
            "Jujutsu.NewChange",
            "Jujutsu.EditChange",
            "Jujutsu.RebaseChangeToolbar",
            "Jujutsu.DescribeChangeToolbar",
            "Jujutsu.MoveChangeUp",
            "Jujutsu.MoveChangeDown"
        ).forEach { (ids.contains(it)) shouldBe true }
    }

    @Test
    fun `withLogEntry supplies the entry and hides the parent's neighbours`() {
        val target = entry()
        val parent = SimpleDataContext.builder()
            .add(JujutsuDataKeys.LOG_ENTRY, entry())
            .add(JujutsuDataKeys.LOG_NEIGHBOURS, JujutsuDataKeys.LogNeighbours(entry(), entry()))
            .build()

        val context = parent.withLogEntry(target)

        JujutsuDataKeys.LOG_ENTRY.getData(context) shouldBe target
        JujutsuDataKeys.LOG_ENTRIES.getData(context) shouldBe listOf(target)
        JujutsuDataKeys.LOG_NEIGHBOURS.getData(context) shouldBe null
    }
}
