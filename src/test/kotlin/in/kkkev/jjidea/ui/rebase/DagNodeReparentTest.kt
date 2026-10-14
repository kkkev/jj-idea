package `in`.kkkev.jjidea.ui.rebase

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.ui.log.DagNode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests [RebaseSimulator]'s reparenting logic directly against a minimal [DagNode] stub - no
 * [in.kkkev.jjidea.jj.JujutsuRepository] mocking, no [in.kkkev.jjidea.jj.LogEntry], no IntelliJ
 * Platform types at all. This is the concrete payoff of extracting [DagNode]: the algorithm's
 * dependency on the concrete jj domain model was already just id + parentIds + one
 * `copy(parentIdentifiers = ...)` call, mirroring how
 * [in.kkkev.jjidea.ui.log.CommitGraphBuilderTest] exercises the graph layout algorithm through a
 * simple string-keyed stub rather than full log entries.
 *
 * [RebaseSimulatorTest] covers the same logic (and more) through the concrete `LogEntry`-typed
 * public [RebaseSimulator.simulate] entry point - this file instead exercises the generic,
 * `internal` reparent functions one level down.
 */
class DagNodeReparentTest {
    private data class Node(override val id: ChangeId, override val parentIds: List<ChangeId> = emptyList()) :
        DagNode<Node> {
        override fun withParents(parentIds: List<ChangeId>) = copy(parentIds = parentIds)
    }

    private fun id(name: String) = ChangeId(name, name)

    private fun node(name: String, vararg parents: String) = Node(id(name), parents.map { id(it) })

    private fun parentNames(node: Node) = node.parentIds.map { it.full }

    @Nested
    inner class `reparentOnto` {
        @Test
        fun `moved root's parents become the destinations`() {
            val a = node("a")
            val moved = node("moved", "a")

            val result = RebaseSimulator.reparentOnto(
                allEntries = listOf(a, moved),
                movedIds = setOf(moved.id),
                movedRoots = setOf(moved.id),
                destinationIds = setOf(a.id)
            )

            parentNames(result.single { it.id == moved.id }) shouldBe listOf("a")
        }

        @Test
        fun `a non-moved child of the moved entry is repointed to the moved entry's original parents`() {
            val a = node("a")
            val moved = node("moved", "a")
            val child = node("child", "moved")

            val result = RebaseSimulator.reparentOnto(
                allEntries = listOf(a, moved, child),
                movedIds = setOf(moved.id),
                movedRoots = setOf(moved.id),
                destinationIds = setOf(a.id)
            )

            // "moved" is no longer between "a" and "child" once it's rebased elsewhere.
            parentNames(result.single { it.id == child.id }) shouldBe listOf("a")
        }
    }

    @Nested
    inner class `reparentInsertAfter` {
        @Test
        fun `moved root becomes a child of the destination`() {
            val dest = node("dest")
            val moved = node("moved")

            val result = RebaseSimulator.reparentInsertAfter(
                allEntries = listOf(dest, moved),
                movedIds = setOf(moved.id),
                movedRoots = setOf(moved.id),
                movedTips = setOf(moved.id),
                destinationIds = setOf(dest.id)
            )

            parentNames(result.single { it.id == moved.id }) shouldBe listOf("dest")
        }

        @Test
        fun `destination's former child is relocated onto the moved tip`() {
            val dest = node("dest")
            val destChild = node("destChild", "dest")
            val moved = node("moved")

            val result = RebaseSimulator.reparentInsertAfter(
                allEntries = listOf(dest, destChild, moved),
                movedIds = setOf(moved.id),
                movedRoots = setOf(moved.id),
                movedTips = setOf(moved.id),
                destinationIds = setOf(dest.id)
            )

            parentNames(result.single { it.id == destChild.id }) shouldBe listOf("moved")
        }
    }

    @Nested
    inner class `reparentInsertBefore` {
        @Test
        fun `moved root takes over the destination's original parents`() {
            val grandparent = node("grandparent")
            val dest = node("dest", "grandparent")
            val moved = node("moved")

            val result = RebaseSimulator.reparentInsertBefore(
                allEntries = listOf(grandparent, dest, moved),
                entryById = listOf(grandparent, dest, moved).associateBy { it.id },
                movedIds = setOf(moved.id),
                movedRoots = setOf(moved.id),
                movedTips = setOf(moved.id),
                destinationIds = setOf(dest.id)
            )

            parentNames(result.single { it.id == moved.id }) shouldBe listOf("grandparent")
        }

        @Test
        fun `destination becomes a child of the moved tip`() {
            val dest = node("dest")
            val moved = node("moved")

            val result = RebaseSimulator.reparentInsertBefore(
                allEntries = listOf(dest, moved),
                entryById = listOf(dest, moved).associateBy { it.id },
                movedIds = setOf(moved.id),
                movedRoots = setOf(moved.id),
                movedTips = setOf(moved.id),
                destinationIds = setOf(dest.id)
            )

            parentNames(result.single { it.id == dest.id }) shouldBe listOf("moved")
        }
    }

    @Nested
    inner class `removeNodes` {
        @Test
        fun `children of a removed node take over its parents`() {
            val base = node("base")
            val mid = node("mid", "base")
            val child = node("child", "mid")

            val result = RebaseSimulator.removeNodes(listOf(child, mid, base), setOf(id("mid")))

            result.map { it.id.full } shouldContainExactly listOf("child", "base")
            parentNames(result.first()) shouldContainExactly listOf("base")
        }

        @Test
        fun `a chain of removed nodes resolves to the first surviving ancestor`() {
            val base = node("base")
            val r1 = node("r1", "base")
            val r2 = node("r2", "r1")
            val child = node("child", "r2")

            val result = RebaseSimulator.removeNodes(listOf(child, r2, r1, base), setOf(id("r1"), id("r2")))

            parentNames(result.first()) shouldContainExactly listOf("base")
        }

        @Test
        fun `untouched nodes are returned as the same instances`() {
            val base = node("base")
            val other = node("other", "base")
            val gone = node("gone", "base")

            val result = RebaseSimulator.removeNodes(listOf(other, gone, base), setOf(id("gone")))

            (result[0] === other) shouldBe true
            (result[1] === base) shouldBe true
        }

        /** Scale deliverable (jj-idea-uuy9): removing half of a 10k chain reads parent lists O(n), not O(n²). */
        @Test
        fun `operation count stays linear on a 10k chain`() {
            var reads = 0
            class Counting(override val id: ChangeId, private val parents: List<ChangeId>) : DagNode<Counting> {
                override val parentIds: List<ChangeId> get() = parents.also { reads++ }
                override fun withParents(parentIds: List<ChangeId>) = Counting(id, parentIds)
            }
            val n = 10_000
            // chain: c(n-1) -> ... -> c0, newest first
            val entries = (n - 1 downTo 0).map { i ->
                Counting(id("c$i"), if (i == 0) emptyList() else listOf(id("c${i - 1}")))
            }
            val removed = (1 until n step 2).map { id("c$it") }.toSet()

            val result = RebaseSimulator.removeNodes(entries, removed)

            result.size shouldBe n - removed.size
            (reads <= 6 * n) shouldBe true
        }
    }

    @Test
    fun `topologicalSort orders children before parents`() {
        val a = node("a")
        val b = node("b", "a")
        val c = node("c", "b")

        val sorted = RebaseSimulator.topologicalSort(listOf(a, b, c))

        sorted.map { it.id.full } shouldContainExactly listOf("c", "b", "a")
    }
}
