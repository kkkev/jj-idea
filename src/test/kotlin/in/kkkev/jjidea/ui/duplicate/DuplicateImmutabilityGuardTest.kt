package `in`.kkkev.jjidea.ui.duplicate

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [invalidDestinationIds] and [validPlacementModes] — the pure logic behind the
 * Duplicate Onto... dialog's immutability guard.
 */
class DuplicateImmutabilityGuardTest {
    private val repo: JujutsuRepository = mockk()

    private fun entry(
        changeId: String,
        parentIds: List<String> = emptyList(),
        immutable: Boolean = false
    ) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId(changeId.padEnd(40, '0')),
        underlyingDescription = "Test commit",
        parentIdentifiers = parentIds.map {
            LogEntry.Identifiers(ChangeId(it, it, null), CommitId(it.padEnd(40, '0')))
        },
        immutable = immutable
    )

    @Nested
    inner class `invalidDestinationIds - onto` {
        @Test
        fun `never excludes anything, even immutable commits`() {
            val trunk = entry("trunk", immutable = true)
            val head = entry("head", parentIds = listOf("trunk"), immutable = true)

            invalidDestinationIds(listOf(trunk, head), RebaseDestinationMode.ONTO) shouldBe emptySet()
        }
    }

    @Nested
    inner class `invalidDestinationIds - insert after` {
        @Test
        fun `allows an immutable head whose children are all mutable`() {
            val head = entry("head", immutable = true)
            val child = entry("child", parentIds = listOf("head"), immutable = false)

            val result = invalidDestinationIds(listOf(head, child), RebaseDestinationMode.INSERT_AFTER)

            (head.id in result) shouldBe false
        }

        @Test
        fun `excludes an immutable commit that has an immutable child`() {
            val trunk = entry("trunk", immutable = true)
            val head = entry("head", parentIds = listOf("trunk"), immutable = true)

            val result = invalidDestinationIds(listOf(trunk, head), RebaseDestinationMode.INSERT_AFTER)

            (trunk.id in result) shouldBe true
        }

        @Test
        fun `excludes a mutable commit with even one immutable child (mixed children)`() {
            val parent = entry("parent", immutable = false)
            val immutableChild = entry("immutableChild", parentIds = listOf("parent"), immutable = true)
            val mutableChild = entry("mutableChild", parentIds = listOf("parent"), immutable = false)

            val result = invalidDestinationIds(
                listOf(parent, immutableChild, mutableChild),
                RebaseDestinationMode.INSERT_AFTER
            )

            (parent.id in result) shouldBe true
        }

        @Test
        fun `never excludes a leaf with no children`() {
            val leaf = entry("leaf", immutable = false)

            val result = invalidDestinationIds(listOf(leaf), RebaseDestinationMode.INSERT_AFTER)

            result shouldBe emptySet()
        }
    }

    @Nested
    inner class `invalidDestinationIds - insert before` {
        @Test
        fun `excludes every immutable commit, including immutable heads`() {
            val head = entry("head", immutable = true)
            val child = entry("child", parentIds = listOf("head"), immutable = false)

            val result = invalidDestinationIds(listOf(head, child), RebaseDestinationMode.INSERT_BEFORE)

            result shouldBe setOf(head.id)
        }

        @Test
        fun `a merge commit's parents' immutability is irrelevant`() {
            val parent1 = entry("parent1", immutable = true)
            val parent2 = entry("parent2", immutable = true)
            val merge = entry("merge", parentIds = listOf("parent1", "parent2"), immutable = false)

            val result = invalidDestinationIds(listOf(parent1, parent2, merge), RebaseDestinationMode.INSERT_BEFORE)

            (merge.id in result) shouldBe false
        }

        @Test
        fun `never excludes a mutable commit`() {
            val mutable = entry("mutable", immutable = false)

            invalidDestinationIds(listOf(mutable), RebaseDestinationMode.INSERT_BEFORE) shouldBe emptySet()
        }
    }

    @Nested
    inner class `validPlacementModes` {
        @Test
        fun `empty selection permits every mode`() {
            val head = entry("head", immutable = true)

            val result = validPlacementModes(listOf(head), emptySet())

            result shouldBe RebaseDestinationMode.entries.toSet()
        }

        @Test
        fun `immutable head with mutable children permits onto and insert-after, not insert-before`() {
            val head = entry("head", immutable = true)
            val child = entry("child", parentIds = listOf("head"), immutable = false)

            val result = validPlacementModes(listOf(head, child), setOf(head.id))

            result shouldBe setOf(RebaseDestinationMode.ONTO, RebaseDestinationMode.INSERT_AFTER)
        }

        @Test
        fun `immutable non-head permits only onto`() {
            val trunk = entry("trunk", immutable = true)
            val head = entry("head", parentIds = listOf("trunk"), immutable = true)

            val result = validPlacementModes(listOf(trunk, head), setOf(trunk.id))

            result shouldBe setOf(RebaseDestinationMode.ONTO)
        }

        @Test
        fun `mutable leaf permits every mode`() {
            val leaf = entry("leaf", immutable = false)

            val result = validPlacementModes(listOf(leaf), setOf(leaf.id))

            result shouldBe RebaseDestinationMode.entries.toSet()
        }

        @Test
        fun `multi-destination selection intersects validity across all selected entries`() {
            val trunk = entry("trunk", immutable = true)
            val head = entry("head", parentIds = listOf("trunk"), immutable = true)
            val leaf = entry("leaf", immutable = false)

            // "trunk" only permits ONTO (it has an immutable child); "leaf" permits everything.
            // The intersection for a merge-destination selection of both must be ONTO only.
            val result = validPlacementModes(listOf(trunk, head, leaf), setOf(trunk.id, leaf.id))

            result shouldBe setOf(RebaseDestinationMode.ONTO)
        }
    }
}
