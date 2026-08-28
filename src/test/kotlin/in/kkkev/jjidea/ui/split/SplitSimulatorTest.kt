package `in`.kkkev.jjidea.ui.split

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SplitSimulatorTest {
    private lateinit var repo: JujutsuRepository

    @BeforeEach
    fun setup() {
        repo = mockk<JujutsuRepository> {
            every { commandExecutor } returns mockk()
            every { displayName } returns "repo"
        }
    }

    private fun entry(
        id: String,
        parents: List<String> = emptyList(),
        description: String = "desc $id",
        isWorkingCopy: Boolean = false
    ) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("0000000000000000000000000000000000000000"),
        underlyingDescription = description,
        bookmarks = emptyList(),
        parentIds = parents.map { ChangeId(it, it, null) },
        isWorkingCopy = isWorkingCopy
    )

    private fun ids(entries: List<LogEntry>) = entries.map { it.id.full }

    private fun parentIds(entry: LogEntry) = entry.parentIds.map { it.full }

    @Nested
    inner class `Linear split` {
        @Test
        fun `source splits into parent and child with correct topology`() {
            // Graph: L -> K -> J -> I
            val i = entry("I")
            val j = entry("J", listOf("I"))
            val k = entry("K", listOf("J"), isWorkingCopy = true)
            val l = entry("L", listOf("K"))
            val allEntries = listOf(l, k, j, i)

            val result = SplitSimulator.simulate(allEntries, k, parallel = false)

            // Parent keeps source's parents
            val parent = result.entries.find { it.id == result.parentId }!!
            parentIds(parent) shouldBe listOf("J")

            // Child is child of parent
            val child = result.entries.find { it.id == result.childId }!!
            parentIds(child) shouldBe listOf(result.parentId.full)
        }

        @Test
        fun `children of source are reparented to child`() {
            // Graph: L -> K -> J
            val j = entry("J")
            val k = entry("K", listOf("J"))
            val l = entry("L", listOf("K"))
            val allEntries = listOf(l, k, j)

            val result = SplitSimulator.simulate(allEntries, k, parallel = false)

            // L was child of K, should now be child of child
            val reparentedL = result.entries.find { it.id.full == "L" }!!
            parentIds(reparentedL) shouldBe listOf(result.childId.full)
        }

        @Test
        fun `source is removed from entries`() {
            val j = entry("J")
            val k = entry("K", listOf("J"))
            val allEntries = listOf(k, j)

            val result = SplitSimulator.simulate(allEntries, k, parallel = false)

            ids(result.entries) shouldContain result.parentId.full
            ids(result.entries) shouldContain result.childId.full
            ids(result.entries).contains("K") shouldBe false
        }

        @Test
        fun `working copy flag preserved on child`() {
            val j = entry("J")
            val k = entry("K", listOf("J"), isWorkingCopy = true)
            val allEntries = listOf(k, j)

            val result = SplitSimulator.simulate(allEntries, k, parallel = false)

            val child = result.entries.find { it.id == result.childId }!!
            child.isWorkingCopy shouldBe true

            val parent = result.entries.find { it.id == result.parentId }!!
            parent.isWorkingCopy shouldBe false
        }

        @Test
        fun `result IDs use source short ID as prefix`() {
            val j = entry("J")
            val k = entry("K", listOf("J"))
            val allEntries = listOf(k, j)

            val result = SplitSimulator.simulate(allEntries, k, parallel = false)

            result.parentId.full shouldBe "K'"
            result.childId.full shouldBe "K''"
        }
    }

    @Nested
    inner class `Parallel split` {
        @Test
        fun `both results are children of source parents`() {
            // Graph: L -> K -> J
            val j = entry("J")
            val k = entry("K", listOf("J"))
            val l = entry("L", listOf("K"))
            val allEntries = listOf(l, k, j)

            val result = SplitSimulator.simulate(allEntries, k, parallel = true)

            val parent = result.entries.find { it.id == result.parentId }!!
            parentIds(parent) shouldBe listOf("J")

            val child = result.entries.find { it.id == result.childId }!!
            parentIds(child) shouldBe listOf("J")
        }

        @Test
        fun `children of source become children of both split results`() {
            // Graph: L -> K -> J
            val j = entry("J")
            val k = entry("K", listOf("J"))
            val l = entry("L", listOf("K"))
            val allEntries = listOf(l, k, j)

            val result = SplitSimulator.simulate(allEntries, k, parallel = true)

            // L was child of K, should now have both parent and child as parents
            val reparentedL = result.entries.find { it.id.full == "L" }!!
            reparentedL.parentIds shouldHaveSize 2
            reparentedL.parentIds.map { it.full }.toSet() shouldBe
                setOf(result.parentId.full, result.childId.full)
        }
    }

    @Nested
    inner class `Topological order` {
        @Test
        fun `entries are sorted children before parents`() {
            // Graph: L -> K -> J -> I
            val i = entry("I")
            val j = entry("J", listOf("I"))
            val k = entry("K", listOf("J"))
            val l = entry("L", listOf("K"))
            val allEntries = listOf(l, k, j, i)

            val result = SplitSimulator.simulate(allEntries, k, parallel = false)

            // L should come before child, child before parent, parent before J, J before I
            val idList = ids(result.entries)
            val lIdx = idList.indexOf("L")
            val childIdx = idList.indexOf(result.childId.full)
            val parentIdx = idList.indexOf(result.parentId.full)
            val jIdx = idList.indexOf("J")

            (lIdx < childIdx) shouldBe true
            (childIdx < parentIdx) shouldBe true
            (parentIdx < jIdx) shouldBe true
        }
    }
}
