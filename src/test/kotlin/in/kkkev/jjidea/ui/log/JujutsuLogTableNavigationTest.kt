package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * jj-idea-7rxm (GitHub #140): an explicit navigation (annotation click) to a change that is
 * loaded but hidden by a filter used to do nothing, silently - expansion can't help, the row
 * is already loaded. It must now report via [JujutsuLogTable.onSelectionHiddenByFilter].
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableNavigationTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>()

    private fun entry(changeId: String, email: String) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "Test commit $changeId",
        parentIds = emptyList(),
        isWorkingCopy = false,
        hasConflict = false,
        isEmpty = false,
        authorTimestamp = null,
        committerTimestamp = null,
        author = VcsUserImpl(email, email),
        committer = null
    )

    private val alice = entry("alice1", "alice@example.com")
    private val bob = entry("bob1", "bob@example.com")

    private fun newTable(): JujutsuLogTable {
        val table = JujutsuLogTable(project.get())
        Disposer.register(project.get(), table)
        table.setEntries(listOf(alice, bob))
        table.logModel.setAuthorFilter(setOf("alice@example.com"))
        return table
    }

    @Test
    fun `loaded but filtered target reports hidden-by-filter without expanding`() {
        val table = newTable()
        val hidden = mutableListOf<ChangeKey>()
        var expansions = 0
        table.onSelectionHiddenByFilter = { hidden += it }
        table.onSelectionExpansionNeeded = { expansions++ }

        table.requestSelection(bob.key)

        hidden shouldBe listOf(bob.key)
        expansions shouldBe 0
        table.selectedRow shouldBe -1
    }

    @Test
    fun `target that loads in later but is filtered reports hidden-by-filter once`() {
        val table = JujutsuLogTable(project.get())
        Disposer.register(project.get(), table)
        table.setEntries(listOf(alice))
        table.logModel.setAuthorFilter(setOf("alice@example.com"))
        val hidden = mutableListOf<ChangeKey>()
        var expansions = 0
        table.onSelectionHiddenByFilter = { hidden += it }
        table.onSelectionExpansionNeeded = { expansions++ }

        table.requestSelection(bob.key)
        expansions shouldBe 1
        table.setEntries(listOf(alice, bob)) // the expansion lands; bob is filtered out

        hidden shouldBe listOf(bob.key)
        expansions shouldBe 1
    }

    @Test
    fun `clearing the filter then requesting again selects the row`() {
        val table = newTable()
        table.onSelectionHiddenByFilter = {}
        table.requestSelection(bob.key)

        table.logModel.setAuthorFilter(emptySet())
        table.requestSelection(bob.key)

        table.logModel.getEntry(table.selectedRow)?.key shouldBe bob.key
    }
}
