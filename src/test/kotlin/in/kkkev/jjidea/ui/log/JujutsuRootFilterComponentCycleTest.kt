package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for the tri-state root filter cycle (jj-idea-qcks, GitHub #96): unset -> included ->
 * excluded -> unset, the Select All / Select None bulk toggle, and the
 * getSelectedRootPaths()/getExcludedRootPaths()/setSelectedRoots() persistence contract used
 * by [UnifiedJujutsuLogPanel].
 *
 * Platform-tagged because [JujutsuFilterComponent.notifyFilterChanged] touches Swing UI state
 * (`initUi()`'s labels) that needs the IDE Application initialized - same rationale as
 * [JujutsuAuthorFilterComponentToggleTest].
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuRootFilterComponentCycleTest {
    private val repo1 = mockk<JujutsuRepository> {
        every { displayName } returns "frontend"
        every { directory.path } returns "/repos/frontend"
    }
    private val repo2 = mockk<JujutsuRepository> {
        every { displayName } returns "backend"
        every { directory.path } returns "/repos/backend"
    }

    private fun entry(changeId: String, repo: JujutsuRepository) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "Test commit $changeId",
        author = VcsUserImpl("Author", "author@example.com")
    )

    private fun component(tableModel: JujutsuLogTableModel) =
        JujutsuRootFilterComponent(tableModel).apply {
            initUi()
            initialize()
        }

    private fun tableModel() = JujutsuLogTableModel().apply {
        setEntries(listOf(entry("aaa111", repo1), entry("bbb222", repo2)))
    }

    private fun ActionGroup.nonSeparatorChildren(): List<AnAction> =
        getChildren(null).filterNot { it is com.intellij.openapi.actionSystem.Separator }

    /** Drives the real popup action for [repo] - `createActionGroup()`'s `CycleRootAction`. */
    private fun cycle(comp: JujutsuRootFilterComponent, repo: JujutsuRepository) {
        val action = comp.createActionGroup().nonSeparatorChildren()
            .first { it.templatePresentation.text == repo.displayName }
        action.actionPerformed(TestActionEvent.createTestEvent(action))
    }

    /** Drives the real popup header action - `createActionGroup()`'s leading `SelectAllRootsAction`. */
    private fun toggleSelectAll(comp: JujutsuRootFilterComponent) {
        val action = comp.createActionGroup().nonSeparatorChildren().first()
        action.actionPerformed(TestActionEvent.createTestEvent(action))
    }

    @Test
    fun `cycling a root through all four states filters correctly at each step`() {
        val model = tableModel()
        val comp = component(model)

        cycle(comp, repo1) // unset -> included
        comp.getSelectedRootPaths() shouldBe setOf(repo1.directory.path)
        comp.getExcludedRootPaths() shouldBe emptySet()
        model.getFilteredEntries().map { it.id.short } shouldBe listOf("aaa111")

        cycle(comp, repo1) // included -> excluded
        comp.getSelectedRootPaths() shouldBe emptySet()
        comp.getExcludedRootPaths() shouldBe setOf(repo1.directory.path)
        model.getFilteredEntries().map { it.id.short } shouldBe listOf("bbb222")

        cycle(comp, repo1) // excluded -> unset
        comp.getSelectedRootPaths() shouldBe emptySet()
        comp.getExcludedRootPaths() shouldBe emptySet()
        model.getFilteredEntries().map { it.id.short }.toSet() shouldBe setOf("aaa111", "bbb222")
    }

    @Test
    fun `included wins when one root is included and another excluded`() {
        val model = tableModel()
        val comp = component(model)

        cycle(comp, repo1) // repo1: unset -> included
        cycle(comp, repo2) // repo2: unset -> included
        cycle(comp, repo2) // repo2: included -> excluded

        comp.getSelectedRootPaths() shouldBe setOf(repo1.directory.path)
        comp.getExcludedRootPaths() shouldBe setOf(repo2.directory.path)
        model.getFilteredEntries().map { it.id.short } shouldBe listOf("aaa111")
    }

    @Test
    fun `select all includes every root and clears exclusions, select none clears everything`() {
        val model = tableModel()
        val comp = component(model)

        cycle(comp, repo1) // repo1: unset -> included
        cycle(comp, repo1) // repo1: included -> excluded
        comp.getExcludedRootPaths() shouldBe setOf(repo1.directory.path)

        // Select All: include every root, clearing exclusions.
        toggleSelectAll(comp)
        comp.getSelectedRootPaths() shouldBe setOf(repo1.directory.path, repo2.directory.path)
        comp.getExcludedRootPaths() shouldBe emptySet()
        model.getFilteredEntries().size shouldBe 2

        // Select None: clear included, no filter active.
        toggleSelectAll(comp)
        comp.getSelectedRootPaths() shouldBe emptySet()
        comp.isActive shouldBe false
        model.getFilteredEntries().size shouldBe 2
    }

    @Test
    fun `cycle action reports Toggleable-selected only in the included state`() {
        val model = tableModel()
        val comp = component(model)

        fun isSelected(): Boolean {
            val action = comp.createActionGroup().nonSeparatorChildren()
                .first { it.templatePresentation.text == repo1.displayName }
            val event = TestActionEvent.createTestEvent(action)
            action.update(event)
            return com.intellij.openapi.actionSystem.Toggleable.isSelected(event.presentation)
        }

        isSelected() shouldBe false // unset
        cycle(comp, repo1) // -> included
        isSelected() shouldBe true
        cycle(comp, repo1) // -> excluded
        isSelected() shouldBe false
    }

    @Test
    fun `setSelectedRoots round-trips through the persistence contract`() {
        val model = tableModel()
        val comp = component(model)

        comp.setSelectedRoots(emptySet(), setOf(repo2.directory.path))

        comp.getSelectedRootPaths() shouldBe emptySet()
        comp.getExcludedRootPaths() shouldBe setOf(repo2.directory.path)
        comp.isActive shouldBe true
        model.getFilteredEntries().map { it.id.short } shouldBe listOf("aaa111")
    }
}
