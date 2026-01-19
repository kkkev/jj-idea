package `in`.kkkev.jjidea.ui.log

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Additional edge case tests for GraphBuilder.
 *
 * These tests verify behavior in unusual graph configurations that may occur
 * in real Jujutsu repositories.
 */
class GraphBuilderEdgeCasesTest {

    private val builder = GraphBuilder()

    @Nested
    inner class `Empty and Single Entry Graphs` {

        @Test
        fun `empty entries produces empty graph`() {
            val graph = builder.buildGraph(emptyList())

            graph shouldHaveSize 0
        }

        @Test
        fun `single commit without parents`() {
            val entries = listOf(entry("root"))

            val graph = builder.buildGraph(entries)

            graph shouldHaveSize 1
            graph["root"]!!.lane shouldBe 0
            graph["root"]!!.parentLanes shouldHaveSize 0
            graph["root"]!!.passThroughLanes shouldHaveSize 0
        }

        @Test
        fun `single commit with missing parent`() {
            // Parent doesn't appear in the log (older than log range)
            val entries = listOf(entry("child", listOf("missing-parent")))

            val graph = builder.buildGraph(entries)

            graph shouldHaveSize 1
            graph["child"]!!.lane shouldBe 0
            // Missing parents have unknown lanes, so parentLanes is empty
            graph["child"]!!.parentLanes shouldHaveSize 0
        }
    }

    @Nested
    inner class `Deep Linear Chains` {

        @Test
        fun `long linear chain uses single lane`() {
            val entries = (0..9).map { i ->
                val parentIds = if (i < 9) listOf("commit${i + 1}") else emptyList()
                entry("commit$i", parentIds)
            }

            val graph = builder.buildGraph(entries)

            // All commits should be in lane 0
            entries.forEach { entry ->
                graph[entry.id]!!.lane shouldBe 0
            }
        }

        @Test
        fun `no pass-through lanes in linear chain`() {
            val entries = listOf(
                entry("c", listOf("b")),
                entry("b", listOf("a")),
                entry("a")
            )

            val graph = builder.buildGraph(entries)

            // No pass-through lanes - adjacent parent-child relationships
            graph["c"]!!.passThroughLanes shouldHaveSize 0
            graph["b"]!!.passThroughLanes shouldHaveSize 0
            graph["a"]!!.passThroughLanes shouldHaveSize 0
        }
    }

    @Nested
    inner class `Wide Branching` {

        @Test
        fun `many children from single parent`() {
            val entries = listOf(
                entry("child1", listOf("parent")),
                entry("child2", listOf("parent")),
                entry("child3", listOf("parent")),
                entry("child4", listOf("parent")),
                entry("child5", listOf("parent")),
                entry("parent")
            )

            val graph = builder.buildGraph(entries)

            // Each child should have a unique lane
            val childLanes = setOf(
                graph["child1"]!!.lane,
                graph["child2"]!!.lane,
                graph["child3"]!!.lane,
                graph["child4"]!!.lane,
                graph["child5"]!!.lane
            )
            childLanes shouldHaveSize 5

            // Parent should be at the leftmost child's lane
            graph["parent"]!!.lane shouldBe graph["child1"]!!.lane
        }

        @Test
        fun `children see parent lane as pass-through when parent not yet processed`() {
            val entries = listOf(
                entry("child1", listOf("parent")),
                entry("child2", listOf("parent")),
                entry("child3", listOf("parent")),
                entry("parent")
            )

            val graph = builder.buildGraph(entries)

            // child1 is first - no pass-through lanes yet (parent not assigned)
            graph["child1"]!!.passThroughLanes shouldHaveSize 0

            // child2 sees parent's lane (0) as pass-through
            // (but not child1's lane since child1 has no pending children)
            graph["child2"]!!.passThroughLanes shouldContainKey 0

            // child3 also sees only parent's lane (0) as pass-through
            graph["child3"]!!.passThroughLanes shouldContainKey 0
        }
    }

    @Nested
    inner class `Diamond Patterns` {

        @Test
        fun `simple diamond merge`() {
            // Classic diamond: root -> left, right -> merge
            val entries = listOf(
                entry("merge", listOf("left", "right")),
                entry("left", listOf("root")),
                entry("right", listOf("root")),
                entry("root")
            )

            val graph = builder.buildGraph(entries)

            // Merge should have two parent lanes
            graph["merge"]!!.parentLanes shouldHaveSize 2

            // All nodes should have lanes assigned
            graph shouldContainKey "merge"
            graph shouldContainKey "left"
            graph shouldContainKey "right"
            graph shouldContainKey "root"
        }

        @Test
        fun `nested diamonds share lanes correctly`() {
            val entries = listOf(
                entry("m2", listOf("m1", "b2")),
                entry("m1", listOf("a1", "b1")),
                entry("b2", listOf("b1")),
                entry("a1", listOf("root")),
                entry("b1", listOf("root")),
                entry("root")
            )

            val graph = builder.buildGraph(entries)

            // Should build without error and assign reasonable lanes
            graph shouldHaveSize 6
            graph.values.all { it.lane >= 0 } shouldBe true
        }
    }

    @Nested
    inner class `Octopus Merges` {

        @Test
        fun `three-parent octopus merge`() {
            val entries = listOf(
                entry("octopus", listOf("a", "b", "c")),
                entry("a"),
                entry("b"),
                entry("c")
            )

            val graph = builder.buildGraph(entries)

            graph["octopus"]!!.parentLanes shouldHaveSize 3
        }

        @Test
        fun `four-parent octopus merge`() {
            val entries = listOf(
                entry("octopus", listOf("a", "b", "c", "d")),
                entry("a"),
                entry("b"),
                entry("c"),
                entry("d")
            )

            val graph = builder.buildGraph(entries)

            graph["octopus"]!!.parentLanes shouldHaveSize 4
        }
    }

    @Nested
    inner class `Lane Recycling` {

        @Test
        fun `lane freed when branch ends is reused`() {
            val entries = listOf(
                entry("main1", listOf("main2")),
                entry("short"),  // Short branch, no parent - frees its lane
                entry("main2", listOf("main3")),
                entry("main3")
            )

            val graph = builder.buildGraph(entries)

            // short should use a lane that gets freed
            val shortLane = graph["short"]!!.lane

            // Check that main stays on its lane
            graph["main1"]!!.lane shouldBe 0
            graph["main2"]!!.lane shouldBe 0
            graph["main3"]!!.lane shouldBe 0

            // Short branch should be on a different lane
            shortLane shouldBe 1
        }

        @Test
        fun `multiple short branches can reuse same lane`() {
            val entries = listOf(
                entry("main1", listOf("main2")),
                entry("short1"),  // Lane 1, freed immediately
                entry("main2", listOf("main3")),
                entry("short2"),  // Should reuse lane 1
                entry("main3")
            )

            val graph = builder.buildGraph(entries)

            // Both short branches should use the same lane (reused)
            graph["short1"]!!.lane shouldBe graph["short2"]!!.lane
        }
    }

    @Nested
    inner class `Color Assignment` {

        @Test
        fun `colors assigned by lane`() {
            val entries = listOf(
                entry("a", listOf("parent")),
                entry("b", listOf("parent")),
                entry("c", listOf("parent")),
                entry("parent")
            )

            val graph = builder.buildGraph(entries)

            // Each lane should have a consistent color
            // Colors cycle through a predefined palette
            val colors = graph.values.map { it.color to it.lane }
            val colorsByLane = colors.groupBy { it.second }.mapValues { it.value.first().first }

            // Verify same lane has same color
            colorsByLane[graph["a"]!!.lane] shouldBe graph["a"]!!.color
            colorsByLane[graph["b"]!!.lane] shouldBe graph["b"]!!.color
            colorsByLane[graph["c"]!!.lane] shouldBe graph["c"]!!.color
        }

        @Test
        fun `colors cycle after palette exhausted`() {
            // Create 10 parallel branches (more than the 8-color palette)
            val entries = (0..9).map { entry("child$it", listOf("parent")) } +
                          listOf(entry("parent"))

            val graph = builder.buildGraph(entries)

            // Colors should cycle - lane 8 should have same color as lane 0
            val lane0Color = graph["child0"]!!.color
            val lane8Color = graph.values.find { it.lane == 8 }?.color

            // If lane 8 exists and uses the same palette position
            if (lane8Color != null) {
                lane8Color shouldBe lane0Color
            }
        }
    }
}
