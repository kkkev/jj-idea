package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [topologicalSort] function.
 *
 * The function ensures log entries are ordered with children before parents,
 * which is required for correct graph rendering.
 */
class TopologicalSortTest {
    private lateinit var repo: JujutsuRepository

    @BeforeEach
    fun setup() {
        repo = mockk<JujutsuRepository> {
            every { commandExecutor } returns mockk()
            every { displayName } returns "test-repo"
        }
    }

    private fun createEntry(
        changeId: String,
        parentIds: List<String> = emptyList(),
        timestamp: Long = 0L
    ) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId.take(2), null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "Commit $changeId",
        parentIdentifiers = parentIds.map {
            LogEntry.Identifiers(ChangeId(it, it.take(2), null), CommitId("0".repeat(40)))
        },
        authorTimestamp = Instant.fromEpochSeconds(timestamp)
    )

    @Nested
    inner class `Basic ordering` {
        @Test
        fun `empty list returns empty`() {
            topologicalSort(emptyList()) shouldBe emptyList()
        }

        @Test
        fun `single entry returns unchanged`() {
            val entry = createEntry("A")
            topologicalSort(listOf(entry)) shouldBe listOf(entry)
        }

        @Test
        fun `child appears before parent`() {
            val parent = createEntry("P")
            val child = createEntry("C", parentIds = listOf("P"))

            // Input in wrong order (parent first)
            val result = topologicalSort(listOf(parent, child))

            result.map { it.id.full } shouldBe listOf("C", "P")
        }

        @Test
        fun `grandchild before child before parent`() {
            val grandparent = createEntry("GP")
            val parent = createEntry("P", parentIds = listOf("GP"))
            val child = createEntry("C", parentIds = listOf("P"))

            // Input in wrong order
            val result = topologicalSort(listOf(grandparent, parent, child))

            result.map { it.id.full } shouldBe listOf("C", "P", "GP")
        }
    }

    @Nested
    inner class `Timestamp tiebreaker` {
        @Test
        fun `unrelated entries sorted by timestamp descending`() {
            val older = createEntry("A", timestamp = 1000)
            val newer = createEntry("B", timestamp = 2000)

            // Input with older first
            val result = topologicalSort(listOf(older, newer))

            // Newer should come first when no DAG relationship
            result.map { it.id.full } shouldBe listOf("B", "A")
        }

        @Test
        fun `DAG order takes precedence over timestamp`() {
            // Child has older timestamp than parent (e.g., after rebase)
            val parent = createEntry("P", timestamp = 2000)
            val child = createEntry("C", parentIds = listOf("P"), timestamp = 1000)

            val result = topologicalSort(listOf(parent, child))

            // Child must still come before parent despite older timestamp
            result.map { it.id.full } shouldBe listOf("C", "P")
        }
    }

    @Nested
    inner class `Complex graphs` {
        @Test
        fun `merge commit with two parents`() {
            val parent1 = createEntry("P1")
            val parent2 = createEntry("P2")
            val merge = createEntry("M", parentIds = listOf("P1", "P2"))

            val result = topologicalSort(listOf(parent1, parent2, merge))

            // Merge should come first
            result.first().id.full shouldBe "M"
            // Both parents should come after merge
            result.drop(1).map { it.id.full }.toSet() shouldBe setOf("P1", "P2")
        }

        @Test
        fun `diamond pattern`() {
            // Diamond: C -> P1 -> GP
            //          C -> P2 -> GP
            val grandparent = createEntry("GP")
            val parent1 = createEntry("P1", parentIds = listOf("GP"))
            val parent2 = createEntry("P2", parentIds = listOf("GP"))
            val child = createEntry("C", parentIds = listOf("P1", "P2"))

            val result = topologicalSort(listOf(grandparent, parent1, parent2, child))

            // Child first, grandparent last
            result.first().id.full shouldBe "C"
            result.last().id.full shouldBe "GP"
        }

        @Test
        fun `entries with parent outside the set are handled`() {
            // Parent not in the list - should not cause issues
            val child = createEntry("C", parentIds = listOf("MISSING"))

            val result = topologicalSort(listOf(child))

            result.map { it.id.full } shouldBe listOf("C")
        }
    }

    @Nested
    inner class `Real-world scenario` {
        @Test
        fun `reproduces the qp-yp bug fix`() {
            // qp (child, 14:07) should appear before yp (parent, 15:21)
            // even though yp has later timestamp
            val yp = createEntry("yp", timestamp = 1521) // 15:21
            val qp = createEntry("qp", parentIds = listOf("yp"), timestamp = 1407) // 14:07

            // This was the bug: timestamp sort put yp first
            val result = topologicalSort(listOf(yp, qp))

            // Fix: qp (child) should come before yp (parent)
            result.map { it.id.full } shouldBe listOf("qp", "yp")
        }
    }
}
