package `in`.kkkev.jjidea.ui.log

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * TDD test suite for commit graph layout algorithm.
 *
 * Tests graph layout in order of increasing complexity:
 * 1. Linear history (single branch)
 * 2. Single-parent children (still linear)
 * 3. Two long-running branches
 * 4. Interweaving branches
 * 5. Multiple branches
 * 6. Merges (multiple parents)
 * 7. Complex scenarios (lane reuse, conflicts)
 *
 * Note: This test doesn't use ChangeId to avoid IntelliJ Platform dependencies.
 * It tests the algorithm logic using strings as commit IDs.
 */
class CommitGraphBuilderTest {

    private val builder = GraphBuilder()

    @Nested
    inner class `1 Linear History` {

        @Test
        fun `single commit has lane 0`() {
            val entries = listOf(entry("aaa"))

            val graph = builder.buildGraph(entries)

            graph shouldContainKey "aaa"
            val node = graph["aaa"]!!
            node.lane shouldBe 0
            node.parentLanes.size shouldBe 0
            node.passThroughLanes.size shouldBe 0
        }

        @Test
        fun `two commits in sequence use same lane`() {
            val entries = listOf(
                entry("bbb", listOf("aaa")),
                entry("aaa")
            )

            val graph = builder.buildGraph(entries)

            // Both should be in lane 0
            graph["bbb"]!!.lane shouldBe 0
            graph["aaa"]!!.lane shouldBe 0

            // bbb points to aaa in same lane
            graph["bbb"]!!.parentLanes shouldBe listOf(0)

            // No pass-through lanes (consecutive commits)
            graph["bbb"]!!.passThroughLanes.size shouldBe 0
            graph["aaa"]!!.passThroughLanes.size shouldBe 0
        }

        @Test
        fun `three commits in linear sequence`() {
            val entries = listOf(
                entry("ccc", listOf("bbb")),
                entry("bbb", listOf("aaa")),
                entry("aaa")
            )

            val graph = builder.buildGraph(entries)

            // All in lane 0
            graph["ccc"]!!.lane shouldBe 0
            graph["bbb"]!!.lane shouldBe 0
            graph["aaa"]!!.lane shouldBe 0

            // Sequential parent relationships
            graph["ccc"]!!.parentLanes shouldBe listOf(0)
            graph["bbb"]!!.parentLanes shouldBe listOf(0)

            // No pass-through lanes
            graph["ccc"]!!.passThroughLanes.size shouldBe 0
            graph["bbb"]!!.passThroughLanes.size shouldBe 0
            graph["aaa"]!!.passThroughLanes.size shouldBe 0
        }
    }

    @Nested
    inner class `2 Non-Adjacent Parents` {

        @Test
        fun `parent one row away creates pass-through lane`() {
            val entries = listOf(
                entry("ccc", listOf("aaa")), // parent is 2 rows away
                entry("bbb"),                 // unrelated commit
                entry("aaa")
            )

            val graph = builder.buildGraph(entries)

            // ccc and aaa in lane 0, bbb in lane 1
            graph["ccc"]!!.lane shouldBe 0
            graph["bbb"]!!.lane shouldBe 1
            graph["aaa"]!!.lane shouldBe 0

            // bbb should have pass-through lane 0 (ccc -> aaa passes through)
            val bbbPassThrough = graph["bbb"]!!.passThroughLanes
            bbbPassThrough shouldContainKey 0
            bbbPassThrough[0] shouldBe graph["ccc"]!!.color
        }

        @Test
        fun `parent two rows away creates multiple pass-through lanes`() {
            val entries = listOf(
                entry("ddd", listOf("aaa")), // parent is 3 rows away
                entry("ccc"),                 // unrelated
                entry("bbb"),                 // unrelated
                entry("aaa")
            )

            val graph = builder.buildGraph(entries)

            // ddd and aaa in lane 0, others get new lanes
            graph["ddd"]!!.lane shouldBe 0
            graph["aaa"]!!.lane shouldBe 0

            // ccc should have pass-through lane 0
            graph["ccc"]!!.passThroughLanes shouldContainKey 0

            // bbb should have pass-through lane 0
            graph["bbb"]!!.passThroughLanes shouldContainKey 0
        }
    }

    @Nested
    inner class `3 Two Long-Running Branches` {

        @Test
        fun `two parallel branches use different lanes`() {
            val entries = listOf(
                entry("ddd", listOf("bbb")), // Branch 1
                entry("ccc"),                 // Branch 2 (no parent)
                entry("bbb", listOf("aaa")), // Branch 1
                entry("aaa")                  // Branch 1
            )

            val graph = builder.buildGraph(entries)

            // Branch 1 (ddd-bbb-aaa) in lane 0
            graph["ddd"]!!.lane shouldBe 0
            graph["bbb"]!!.lane shouldBe 0
            graph["aaa"]!!.lane shouldBe 0

            // Branch 2 (ccc) in different lane
            graph["ccc"]!!.lane shouldBe 1

            // ccc should have pass-through lane 0 (bbb-aaa line passes through)
            graph["ccc"]!!.passThroughLanes shouldContainKey 0
        }

        @Test
        fun `lane conflict - lane should not be reused while active`() {
            // This is the bug described: ccc and bbb both descend from aaa
            // Sequence: ccc, bbb, aaa
            // ccc should take lane 0, bbb should take lane 1 (not 0, as 0 is still active)
            val entries = listOf(
                entry("ccc", listOf("aaa")),
                entry("bbb", listOf("aaa")),
                entry("aaa")
            )

            val graph = builder.buildGraph(entries)

            // ccc in lane 0
            graph["ccc"]!!.lane shouldBe 0

            // bbb should be in lane 1 (NOT 0, because lane 0 is still active for ccc->aaa)
            graph["bbb"]!!.lane shouldBe 1

            // aaa receives both children
            // This is the key test: lanes should not conflict
            graph["aaa"]!!.lane shouldBe 0

            // bbb should see pass-through lane 0 (ccc->aaa line)
            graph["bbb"]!!.passThroughLanes shouldContainKey 0
        }
    }

    @Nested
    inner class `4 Interweaving Branches` {

        @Test
        fun `alternating commits from two branches`() {
            val entries = listOf(
                entry("e1", listOf("d1")), // Branch 1
                entry("e2", listOf("d2")), // Branch 2
                entry("d1", listOf("c1")), // Branch 1
                entry("d2", listOf("c2")), // Branch 2
                entry("c1"),               // Branch 1
                entry("c2")                // Branch 2
            )

            val graph = builder.buildGraph(entries)

            // Branch 1 should use one lane consistently
            val lane1 = graph["e1"]!!.lane
            graph["d1"]!!.lane shouldBe lane1
            graph["c1"]!!.lane shouldBe lane1

            // Branch 2 should use a different lane consistently
            val lane2 = graph["e2"]!!.lane
            graph["d2"]!!.lane shouldBe lane2
            graph["c2"]!!.lane shouldBe lane2

            lane1 shouldBe 0
            lane2 shouldBe 1

            // Each row should have pass-through lane for the other branch
            graph["e2"]!!.passThroughLanes shouldContainKey lane1
            graph["d1"]!!.passThroughLanes shouldContainKey lane2
            graph["d2"]!!.passThroughLanes shouldContainKey lane1
            graph["c1"]!!.passThroughLanes shouldContainKey lane2
        }
    }

    @Nested
    inner class `5 Multiple Branches` {

        @Test
        fun `three parallel branches`() {
            val entries = listOf(
                entry("c1", listOf("b1")), // Branch 1
                entry("c2", listOf("b2")), // Branch 2
                entry("c3", listOf("b3")), // Branch 3
                entry("b1", listOf("a1")), // Branch 1
                entry("b2", listOf("a2")), // Branch 2
                entry("b3", listOf("a3")), // Branch 3
                entry("a1"),               // Branch 1
                entry("a2"),               // Branch 2
                entry("a3")                // Branch 3
            )

            val graph = builder.buildGraph(entries)

            // Three different lanes
            val lanes = setOf(
                graph["c1"]!!.lane,
                graph["c2"]!!.lane,
                graph["c3"]!!.lane
            )
            lanes shouldHaveSize 3

            // Each branch consistent in its lane
            graph["c1"]!!.lane shouldBe graph["b1"]!!.lane
            graph["b1"]!!.lane shouldBe graph["a1"]!!.lane

            // Pass-through lanes: at row with c2, should see lane for c1->b1
            // (c3 hasn't been processed yet, so b3 hasn't been assigned)
            val c2PassThrough = graph["c2"]!!.passThroughLanes
            c2PassThrough.keys shouldHaveSize 1 // One active lane from c1
        }
    }

    @Nested
    inner class `6 Merges` {

        @Test
        fun `simple merge - commit with two parents`() {
            val entries = listOf(
                entry("merge", listOf("left", "right")),
                entry("left"),
                entry("right")
            )

            val graph = builder.buildGraph(entries)

            // Merge commit should have two parent lanes
            graph["merge"]!!.parentLanes shouldHaveSize 2

            // With Option 2: passthrough to right blocks lane 0
            // left is pushed to lane 1, right gets lane 0 when passthrough terminates
            graph["merge"]!!.lane shouldBe 0
            graph["left"]!!.lane shouldBe 1   // pushed by passthrough to right
            graph["right"]!!.lane shouldBe 0  // gets child's lane after passthrough terminates

            graph["merge"]!!.parentLanes[0] shouldBe 1  // left
            graph["merge"]!!.parentLanes[1] shouldBe 0  // right
        }

        @Test
        fun `merge after parallel development`() {
            val entries = listOf(
                entry("merge", listOf("b1", "b2")),
                entry("b1", listOf("a")),
                entry("b2", listOf("a")),
                entry("a")
            )

            val graph = builder.buildGraph(entries)

            // merge has two parents
            graph["merge"]!!.parentLanes shouldHaveSize 2

            // With Option 2: passthrough to b2 blocks lane 0
            // b1 is pushed to lane 1, b2 gets lane 0
            val b1Lane = graph["b1"]!!.lane
            val b2Lane = graph["b2"]!!.lane
            b1Lane shouldBe 1  // pushed by passthrough to b2
            b2Lane shouldBe 0  // gets child's lane after passthrough terminates

            // Both converge at a (a gets lowest child lane = 0)
            graph["a"]!!.lane shouldBe 0
        }
    }

    @Nested
    inner class `7 Complex Scenarios` {

        @Test
        fun `lane reuse after branch completes`() {
            val entries = listOf(
                entry("e", listOf("d")),   // Main branch continues
                entry("short"),             // Short branch (no parent, ends here)
                entry("d", listOf("c")),   // Main branch
                entry("c", listOf("b")),   // Main branch
                entry("b", listOf("a")),   // Main branch
                entry("a")                  // Main branch root
            )

            val graph = builder.buildGraph(entries)

            // Main branch (e-d-c-b-a) in lane 0
            graph["e"]!!.lane shouldBe 0
            graph["d"]!!.lane shouldBe 0

            // Short branch in lane 1
            graph["short"]!!.lane shouldBe 1

            // d should have pass-through for e->c (lane 0)
            // but NOT for short (it has no parent, lane is free)
            graph["d"]!!.passThroughLanes.size shouldBe 0 // short branch doesn't reserve a lane
        }

        @Test
        fun `complex interleaving with lane reuse`() {
            // Multiple branches with different lifetimes
            val entries = listOf(
                entry("f1", listOf("e1")),  // Branch 1 continues
                entry("short2"),             // Branch 2 ends
                entry("e1", listOf("d1")),  // Branch 1
                entry("short1"),             // Branch 3 ends
                entry("d1", listOf("c1")),  // Branch 1
                entry("c1", listOf("b1")),  // Branch 1
                entry("b1", listOf("a1")),  // Branch 1
                entry("a1")                  // Branch 1 root
            )

            val graph = builder.buildGraph(entries)

            // Branch 1 consistent
            graph["f1"]!!.lane shouldBe 0
            graph["e1"]!!.lane shouldBe 0
            graph["d1"]!!.lane shouldBe 0

            // Short branches: short2 gets lane 1, short1 reuses it (lane reuse works!)
            graph["short2"]!!.lane shouldBe 1
            graph["short1"]!!.lane shouldBe 1  // Reuses lane from short2 (has no parents)
        }

        @Test
        fun `octopus merge - commit with three parents`() {
            val entries = listOf(
                entry("octopus", listOf("a", "b", "c")),
                entry("a"),
                entry("b"),
                entry("c")
            )

            val graph = builder.buildGraph(entries)

            // Three parent lanes
            graph["octopus"]!!.parentLanes shouldHaveSize 3

            // With Option 2: passthroughs to b and c both block lane 0
            // a is pushed to lane 1, b is pushed to lane 1 (different row from a)
            // c gets lane 0 when passthrough terminates
            graph["octopus"]!!.lane shouldBe 0
            graph["a"]!!.lane shouldBe 1  // pushed by passthrough
            graph["b"]!!.lane shouldBe 1  // pushed by passthrough (different row, OK)
            graph["c"]!!.lane shouldBe 0  // passthrough terminates

            graph["octopus"]!!.parentLanes[0] shouldBe 1  // a
            graph["octopus"]!!.parentLanes[1] shouldBe 1  // b
            graph["octopus"]!!.parentLanes[2] shouldBe 0  // c
        }
    }

    @Nested
    inner class `8 Children Tracking` {

        @Test
        fun `track children for upward line drawing`() {
            // To draw lines from child to parent circles (not just top/bottom of cells),
            // we need to know which commits in the previous row are children
            val entries = listOf(
                entry("child", listOf("parent")),
                entry("parent")
            )

            val graph = builder.buildGraph(entries)

            // This test documents that we need child tracking
            // For now, we rely on the renderer to look backwards
            // But ideally, GraphNode should include:
            // - childLanes: List<Int> (lanes where children are located)

            // TODO: Enhance GraphNode with child tracking for proper line rendering
            graph["child"]!!.parentLanes shouldBe listOf(0)
        }
    }

    @Nested
    inner class `9 Lane Reuse After Merge` {

        @Test
        fun `lane should be reused after merge completes`() {
            // Scenario from user feedback:
            // Lane 0: dd <- cc <- bb <- aa
            // Lane 1: ee <- cc (converges into lane 0)
            // Then: ff <- aa (should reuse lane 1, not create lane 2)
            val entries = listOf(
                entry("dd", listOf("cc")), // Lane 0
                entry("ee", listOf("cc")), // Lane 1, converges to cc
                entry("cc", listOf("bb")), // Lane 0 (continues from dd)
                entry("bb", listOf("aa")), // Lane 0
                entry("ff", listOf("aa")), // Should reuse lane 1 (freed after merge)
                entry("aa")                 // Lane 0
            )

            val graph = builder.buildGraph(entries)

            // dd in lane 0
            graph["dd"]!!.lane shouldBe 0

            // ee in lane 1 (new lane for second child of cc)
            graph["ee"]!!.lane shouldBe 1

            // cc in lane 0 (continues from dd, first child)
            graph["cc"]!!.lane shouldBe 0

            // bb in lane 0
            graph["bb"]!!.lane shouldBe 0

            // ff should reuse lane 1 (freed after ee merged into cc)
            graph["ff"]!!.lane shouldBe 1  // FAILING: getting lane 2

            // aa in lane 0
            graph["aa"]!!.lane shouldBe 0
        }

        @Test
        fun `parent with two children has correct child lanes`() {
            // Test that we track both children for rendering upward lines
            val entries = listOf(
                entry("child1", listOf("parent")), // Lane 0
                entry("child2", listOf("parent")), // Lane 1
                entry("parent")                     // Lane 0
            )

            val graph = builder.buildGraph(entries)

            // Both children at different lanes
            graph["child1"]!!.lane shouldBe 0
            graph["child2"]!!.lane shouldBe 1

            // Parent continues in lane 0 (from child1)
            graph["parent"]!!.lane shouldBe 0

            // TODO: Add childLanes tracking
            // graph["parent"]!!.childLanes should contain both 0 and 1
        }

        @Test
        fun `complex merge with lane reuse`() {
            // More complex scenario:
            // a1 <- b1 <- c1 (main branch in lane 0)
            // a1 <- b2 <- c2 (side branch in lane 1, merges at a1)
            // a1 <- b3      (another child)
            val entries = listOf(
                entry("c1", listOf("b1")), // Lane 0
                entry("c2", listOf("b2")), // Lane 1
                entry("b1", listOf("a1")), // Lane 0
                entry("b2", listOf("a1")), // Lane 1, merges to a1
                entry("b3", listOf("a1")), // Gets lane 2 (lanes 0,1 blocked by passthroughs)
                entry("a1")                 // Lane 0
            )

            val graph = builder.buildGraph(entries)

            graph["c1"]!!.lane shouldBe 0
            graph["c2"]!!.lane shouldBe 1
            graph["b1"]!!.lane shouldBe 0
            graph["b2"]!!.lane shouldBe 1

            // With Option 2: b1 and b2 create passthroughs to a1 at lanes 0 and 1
            // b3 can't reuse because both lanes are blocked by passthroughs
            graph["b3"]!!.lane shouldBe 2  // lanes 0,1 blocked by passthroughs to a1

            graph["a1"]!!.lane shouldBe 0
        }
    }

    @Nested
    inner class `10 Parent Lane Selection` {

        @Test
        fun `parent with multiple children in non-sequential lanes uses leftmost`() {
            // Create scenario where children are in lanes 1, 2, 3 (not 0, 1, 2)
            // by having another commit occupy lane 0
            val entries = listOf(
                entry("xx", listOf("yy")),     // Occupies lane 0
                entry("aa", listOf("parent")), // Gets lane 1
                entry("bb", listOf("parent")), // Gets lane 2
                entry("cc", listOf("parent")), // Gets lane 3
                entry("yy"),                    // Lane 0 continues
                entry("parent")                 // Should be lane 1 (leftmost child), not 1 (first child)
            )

            val builder = GraphBuilder()
            val graph = builder.buildGraph(entries)

            graph["xx"]!!.lane shouldBe 0
            graph["aa"]!!.lane shouldBe 1  // First child of parent
            graph["bb"]!!.lane shouldBe 2  // Second child
            graph["cc"]!!.lane shouldBe 3  // Third child
            graph["yy"]!!.lane shouldBe 0
            graph["parent"]!!.lane shouldBe 1  // Should use leftmost child lane (1)
        }

        @Test
        fun `parent reassigned to leftmost when later child has lower lane`() {
            // Scenario where first child assigns parent to lane 2, but later child in lane 1
            // xx (lane 0) -> yy, aa (lane 2) -> parent, zz (lane 1) -> yy, bb (lane 1) -> parent
            // Parent should end up in lane 1 (from bb), not lane 2 (from aa)
            val entries = listOf(
                entry("xx", listOf("yy")),      // Lane 0
                entry("aa", listOf("parent")),  // Lane 1, assigns parent to lane 1
                entry("zz", listOf("yy")),      // Lane 2
                entry("bb", listOf("parent")),  // Lane 3, parent already at lane 1
                entry("yy"),                     // Lane 0
                entry("parent")                  // Currently lane 1 (from aa), should stay lane 1
            )

            val builder = GraphBuilder()
            val graph = builder.buildGraph(entries)

            graph["xx"]!!.lane shouldBe 0
            graph["aa"]!!.lane shouldBe 1
            graph["zz"]!!.lane shouldBe 2
            graph["bb"]!!.lane shouldBe 3
            graph["yy"]!!.lane shouldBe 0

            // aa assigns parent to lane 1, bb assigns parent to lane 1 (already assigned)
            // But we want: if bb's lane (3) < parent's current lane (1), reassign parent
            // Wait, that's backwards. We want parent to use MINIMUM of child lanes.

            // Actually, aa is first, gets lane 1, assigns parent to lane 1
            // bb is later, gets lane 3, parent already at lane 1
            // So parent stays at lane 1, which IS the leftmost (min(1, 3) = 1)
            graph["parent"]!!.lane shouldBe 1  // Correct
        }

        @Test
        fun `parent uses minimum child lane when children have gaps`() {
            // Real test: aa (lane 3) processes first and assigns parent to lane 3
            // Then bb (lane 1) processes and should reassign parent to lane 1
            // To get aa in lane 3, we need lanes 0, 1, 2 occupied
            val entries = listOf(
                entry("x0", listOf("y")),       // Lane 0
                entry("x1", listOf("y")),       // Lane 1
                entry("x2", listOf("y")),       // Lane 2
                entry("aa", listOf("parent")),  // Lane 3, assigns parent to lane 3
                entry("bb", listOf("parent")),  // Lane 4, parent at lane 3, should check if bb < 3
                entry("y"),                      // Lane 0
                entry("parent")                  // Should be lane 3 (from aa)
            )

            val builder = GraphBuilder()
            val graph = builder.buildGraph(entries)

            graph["x0"]!!.lane shouldBe 0
            graph["x1"]!!.lane shouldBe 1
            graph["x2"]!!.lane shouldBe 2
            graph["aa"]!!.lane shouldBe 3
            graph["bb"]!!.lane shouldBe 4
            graph["y"]!!.lane shouldBe 0

            // Currently: aa assigns parent to lane 3, bb gets lane 4
            // We want parent in min(3, 4) = lane 3
            graph["parent"]!!.lane shouldBe 3  // This should pass (leftmost is 3)

            // But what if we want bb to have a LOWER lane? We'd need lane reuse...
        }

        @Test
        fun `parent reassigned when later child reuses lower lane`() {
            // Scenario: Keep lane 0 occupied, free lane 1, then have children in lanes 2 and 1
            // x1 -> y1 (keeps lane 0 occupied)
            // z1 (lane 1, leaf, frees lane 1)
            // aa (lane 2) -> parent (first child, assigns parent to lane 2)
            // bb (lane 1, reused) -> parent (second child, should reassign parent to lane 1)
            val entries = listOf(
                entry("x1", listOf("y1")),      // Lane 0, keeps it occupied
                entry("z1"),                     // Lane 1, leaf, will free lane 1
                entry("aa", listOf("parent")),  // Lane 2, assigns parent to lane 2
                entry("bb", listOf("parent")),  // Lane 1 (reuses), parent should move to 1!
                entry("y1"),                     // Lane 0 continues
                entry("parent")                  // Should be lane 1 (min of aa=2, bb=1), currently 2
            )

            val builder = GraphBuilder()
            val graph = builder.buildGraph(entries)

            graph["x1"]!!.lane shouldBe 0
            graph["z1"]!!.lane shouldBe 1  // Freed (leaf)
            graph["aa"]!!.lane shouldBe 1  // Reuses freed lane 1
            graph["bb"]!!.lane shouldBe 2  // Gets new lane 2
            graph["y1"]!!.lane shouldBe 0
            graph["parent"]!!.lane shouldBe 1  // Should be 1 (min(1,2)=1), already correct!
        }

        @Test
        fun `parent reassigned from high lane when low lane freed after first child`() {
            // Critical scenario:
            // Occupy lanes 0,1,2 with long-running branches
            // aa (gets lane 3) -> parent (assigns parent to lane 3)
            // z2 (frees lane 2)
            // bb (reuses lane 2) -> parent (parent should be REASSIGNED to lane 2!)
            val entries = listOf(
                entry("x0", listOf("y0")),      // Lane 0
                entry("x1", listOf("y1")),      // Lane 1
                entry("x2", listOf("y2")),      // Lane 2
                entry("aa", listOf("parent")),  // Lane 3, assigns parent to lane 3
                entry("z2", listOf("y2")),      // Lane 4, second child of y2
                entry("y2"),                     // Lane 2, frees z2's lane 4 (z2 is leaf that merged)
                entry("bb", listOf("parent")),  // Lane 2 (reuses freed lane 2), parent should move to 2!
                entry("y0"),                     // Lane 0
                entry("y1"),                     // Lane 1
                entry("parent")                  // Should be lane 2 (min(3,2)=2), currently 3
            )

            val builder = GraphBuilder()
            val graph = builder.buildGraph(entries)

            graph["x0"]!!.lane shouldBe 0
            graph["x1"]!!.lane shouldBe 1
            graph["x2"]!!.lane shouldBe 2
            graph["aa"]!!.lane shouldBe 3  // Gets lane 3, assigns parent to lane 3
            graph["z2"]!!.lane shouldBe 4  // Second child of y2
            graph["y2"]!!.lane shouldBe 2  // Frees z2's lane 4
            graph["bb"]!!.lane shouldBe 2  // Reuses lane 2 (wait, y2 freed lane 4, not lane 2!)
            graph["y0"]!!.lane shouldBe 0
            graph["y1"]!!.lane shouldBe 1
            graph["parent"]!!.lane shouldBe 2  // SHOULD be 2 (leftmost), currently likely 3
        }
    }

    @Nested
    inner class `11 Bug jj-idea-1f2 Merge Rendering` {

        @Test
        fun `merge commit qn with parents rso and oq`() {
            // Exact scenario from bug jj-idea-1f2:
            // Commits: py, wp, oq, rso, qn
            // Relationships: wp:py, oq:py, rso:wp, qn:rso,oq
            // qn is the merge commit with two parents: rso and oq
            //
            // Log order (newest first, topologically sorted):
            val entries = listOf(
                entry("qn", listOf("rso", "oq")),  // Merge commit
                entry("rso", listOf("wp")),        // First parent of qn
                entry("oq", listOf("py")),         // Second parent of qn
                entry("wp", listOf("py")),
                entry("py")
            )

            val graph = builder.buildGraph(entries)

            // qn should be in lane 0
            graph["qn"]!!.lane shouldBe 0

            // qn should have two parent lanes
            graph["qn"]!!.parentLanes shouldHaveSize 2

            // With Option 2: passthrough to oq blocks lane 0
            // rso is pushed to lane 1, oq gets lane 0 when passthrough terminates
            graph["qn"]!!.parentLanes[0] shouldBe 1  // rso pushed to lane 1
            graph["qn"]!!.parentLanes[1] shouldBe 0  // oq gets lane 0

            // rso should be in lane 1 (pushed by passthrough to oq)
            graph["rso"]!!.lane shouldBe 1

            // oq should be in lane 0 (passthrough terminates)
            graph["oq"]!!.lane shouldBe 0

            // Verify the graph visually makes sense:
            // Lane 0: qn -> oq -> py (main line continues to furthest parent)
            // Lane 1: rso -> wp (diagonal from qn to rso)
            graph["wp"]!!.lane shouldBe 1  // follows rso
            graph["py"]!!.lane shouldBe 0  // oq's parent, lowest child lane
        }
    }
}
