package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [JujutsuLogTable.uiDataSnapshot] publishing [JujutsuDataKeys.LOG_NEIGHBOURS]
 * (jj-idea-owje, GitHub #93 - backs Move Up/Down): the selected entry's single child/parent in
 * the commit *graph*, published only for a single-row selection - not display-row adjacency,
 * which a multi-root log's timestamp-interleaved ordering makes meaningless across repos.
 *
 * Platform-tagged because it exercises a real Swing [JujutsuLogTable]/[com.intellij.ui.table.JBTable]
 * selection model, which needs IJPGP's full platform classpath (see project memory on IJPGP test
 * infrastructure).
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableNeighboursTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>()

    private fun entry(repo: JujutsuRepository, changeId: String, parentIds: List<String> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "Test commit $changeId",
        parentIds = parentIds.map { ChangeId(it, it, null) },
        isWorkingCopy = false,
        hasConflict = false,
        isEmpty = false,
        authorTimestamp = null,
        committerTimestamp = null,
        author = null,
        committer = null
    )

    private fun entry(changeId: String, parentIds: List<String> = emptyList()) = entry(repo, changeId, parentIds)

    private fun tableWith(entries: List<LogEntry>): JujutsuLogTable {
        val table = JujutsuLogTable(project.get())
        Disposer.register(project.get(), table)
        table.setEntries(entries)
        return table
    }

    @Test
    fun `linear chain resolves single child and single parent`() {
        val a = entry("a")
        val b = entry("b", parentIds = listOf("a"))
        val c = entry("c", parentIds = listOf("b"))
        val table = tableWith(listOf(c, b, a))
        table.setRowSelectionInterval(1, 1)

        val sink = mockk<DataSink>(relaxed = true)
        table.uiDataSnapshot(sink)

        verify {
            sink[JujutsuDataKeys.LOG_NEIGHBOURS] = JujutsuDataKeys.LogNeighbours(singleChild = c, singleParent = a)
        }
    }

    @Test
    fun `a commit with two children has no single child`() {
        val root = entry("root")
        val childA = entry("a", parentIds = listOf("root"))
        val childB = entry("b", parentIds = listOf("root"))
        val table = tableWith(listOf(childA, childB, root))
        table.setRowSelectionInterval(2, 2)

        val sink = mockk<DataSink>(relaxed = true)
        table.uiDataSnapshot(sink)

        verify {
            sink[JujutsuDataKeys.LOG_NEIGHBOURS] =
                JujutsuDataKeys.LogNeighbours(singleChild = null, singleParent = null)
        }
    }

    @Test
    fun `a merge commit has no single parent`() {
        val parentA = entry("a")
        val parentB = entry("b")
        val merge = entry("m", parentIds = listOf("a", "b"))
        val table = tableWith(listOf(merge, parentA, parentB))
        table.setRowSelectionInterval(0, 0)

        val sink = mockk<DataSink>(relaxed = true)
        table.uiDataSnapshot(sink)

        verify {
            sink[JujutsuDataKeys.LOG_NEIGHBOURS] =
                JujutsuDataKeys.LogNeighbours(singleChild = null, singleParent = null)
        }
    }

    @Test
    fun `a filtered-out child is still resolved`() {
        val a = entry("a")
        val b = entry("b", parentIds = listOf("a"))
        val table = tableWith(listOf(b, a))
        table.logModel.setFilter("a") // hides b from the displayed/filtered rows
        table.setRowSelectionInterval(0, 0) // the only remaining visible row is a

        val sink = mockk<DataSink>(relaxed = true)
        table.uiDataSnapshot(sink)

        verify {
            sink[JujutsuDataKeys.LOG_NEIGHBOURS] =
                JujutsuDataKeys.LogNeighbours(singleChild = b, singleParent = null)
        }
    }

    @Test
    fun `multi-repo interleaving never crosses repos`() {
        val repoA = mockk<JujutsuRepository>()
        val repoB = mockk<JujutsuRepository>()
        val parentA = entry(repoA, "a1")
        val childA = entry(repoA, "a2", parentIds = listOf("a1"))
        // repoB's commit is displayed directly between repoA's parent and child - a real
        // multi-root log interleaves rows by timestamp with no relation to any other repo's DAG.
        val table = tableWith(listOf(childA, entry(repoB, "b1"), parentA))

        table.setRowSelectionInterval(0, 0) // childA
        val sinkChild = mockk<DataSink>(relaxed = true)
        table.uiDataSnapshot(sinkChild)
        verify {
            sinkChild[JujutsuDataKeys.LOG_NEIGHBOURS] =
                JujutsuDataKeys.LogNeighbours(singleChild = null, singleParent = parentA)
        }

        table.setRowSelectionInterval(2, 2) // parentA
        val sinkParent = mockk<DataSink>(relaxed = true)
        table.uiDataSnapshot(sinkParent)
        verify {
            sinkParent[JujutsuDataKeys.LOG_NEIGHBOURS] =
                JujutsuDataKeys.LogNeighbours(singleChild = childA, singleParent = null)
        }
    }

    @Test
    fun `multi-row selection publishes no neighbours`() {
        val a = entry("a")
        val b = entry("b")
        val table = tableWith(listOf(a, b))
        table.setRowSelectionInterval(0, 1)

        val sink = mockk<DataSink>(relaxed = true)
        table.uiDataSnapshot(sink)

        verify(exactly = 0) { sink[JujutsuDataKeys.LOG_NEIGHBOURS] = any() }
    }
}
