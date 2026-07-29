package `in`.kkkev.jjidea.ui.log

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for the "Filter Log by ..." toggle contract (jj-idea-iesq): triggering the author-filter
 * action again for the author who is already the active filter clears it, mirroring the same
 * toggle idiom as [JujutsuReferenceFilterComponentSelectReferenceTest]'s bookmark/tag coverage.
 * `CommitTablePanel.createFilterComponents` wires this as
 * `if (getSelectedAuthors() == setOf(email)) setSelectedAuthors(emptySet()) else setSelectedAuthors(setOf(email))`
 * against [JujutsuAuthorFilterComponent] directly - this test exercises that same decision. Also
 * covers that [in.kkkev.jjidea.jj.JujutsuStateModel.activeAuthorFilter] stays in sync, since that's
 * now the single source of truth the "Filter Log by ..." checkmark reads.
 *
 * Platform-tagged because [JujutsuFilterComponent.notifyFilterChanged] touches Swing UI state
 * (`initUi()`'s labels) that needs the IDE Application initialized.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuAuthorFilterComponentToggleTest {
    private val projectFixture = projectFixture()
    private val repo = mockk<JujutsuRepository>()
    private val alice = "alice@example.com"
    private val bob = "bob@example.com"

    private fun entry(changeId: String, authorEmail: String) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "Test commit $changeId",
        author = VcsUserImpl("Author", authorEmail)
    )

    private fun component(tableModel: JujutsuLogTableModel) =
        JujutsuAuthorFilterComponent(tableModel, projectFixture.get()).apply {
            initUi()
            initialize()
        }

    private fun toggle(comp: JujutsuAuthorFilterComponent, email: String) {
        if (comp.getSelectedAuthors() == setOf(email)) {
            comp.setSelectedAuthors(emptySet())
        } else {
            comp.setSelectedAuthors(setOf(email))
        }
    }

    @Test
    fun `toggling the same author twice clears the filter`() {
        val tableModel = JujutsuLogTableModel()
        tableModel.setEntries(listOf(entry("aaa111", alice), entry("bbb222", bob)))
        val comp = component(tableModel)

        toggle(comp, alice)
        tableModel.getFilteredEntries().size shouldBe 1
        comp.getSelectedAuthors() shouldBe setOf(alice)
        projectFixture.get().stateModel.activeAuthorFilter shouldBe setOf(alice)

        toggle(comp, alice)
        tableModel.getFilteredEntries().size shouldBe 2
        comp.getSelectedAuthors() shouldBe emptySet()
        projectFixture.get().stateModel.activeAuthorFilter shouldBe emptySet()
    }

    @Test
    fun `toggling a different author while one is active switches instead of clearing`() {
        val tableModel = JujutsuLogTableModel()
        tableModel.setEntries(listOf(entry("aaa111", alice), entry("bbb222", bob)))
        val comp = component(tableModel)

        toggle(comp, alice)
        toggle(comp, bob)

        comp.getSelectedAuthors() shouldBe setOf(bob)
        tableModel.getFilteredEntries().size shouldBe 1
        tableModel.getFilteredEntries().first().id.short shouldBe "bbb222"
    }
}
