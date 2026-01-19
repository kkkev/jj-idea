package `in`.kkkev.jjidea.ui.log.graph

import `in`.kkkev.jjidea.jj.ChangeId
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.collections.emptyList

/*
  #: 1
  Test Name: Single entry, no parents
  New Concept: Basic lane assignment
  Entries: A
  ────────────────────────────────────────
  #: 2
  Test Name: Two entries, linear
  New Concept: Parent-child, adjacent rows
  Entries: A→B
  ────────────────────────────────────────
  #: 3
  Test Name: Three entries, linear
  New Concept: Chain of entries
  Entries: A→B→C
  ────────────────────────────────────────
  #: 4
  Test Name: Two entries with gap
  New Concept: Passthroughs
  Entries: A→C (B unrelated)
  ────────────────────────────────────────
  #: 5
  Test Name: Simple fork
  New Concept: Multiple children, one parent
  Entries: A,B→C
  ────────────────────────────────────────
  #: 6
  Test Name: Simple merge
  New Concept: One child, multiple parents
  Entries: A→B,C
  ────────────────────────────────────────
  #: 7
  Test Name: Fork with passthrough
  New Concept: Fork + passthrough interaction
  Entries: A→C, B→C (A skips B)
  ────────────────────────────────────────
  #: 8
  Test Name: Diamond pattern
  New Concept: Fork + merge combined
  Entries: A→B,E; B→C→D→G; E→F→G
  ────────────────────────────────────────
  #: 9
  Test Name: Lane conflict
  New Concept: Passthrough blocks preferred lane
  Entries: Covered by #7, #8
  ────────────────────────────────────────
  #: 10
  Test Name: Multiple independent branches
  New Concept: Parallel passthroughs
  Entries: A→D, B→E, C→F


  TDD Implementation Order

  1. Test 1 → Implement basic structure, always return lane 0
  2. Test 2 → Implement childrenByParent tracking, lane follows child
  3. Test 3 → Verify chaining works (should pass with test 2 implementation)
  4. Test 4 → Implement passthrough creation and "lowest available lane" logic
  5. Test 5 → Implement multiple children handling (should mostly work)
  6. Test 6 → Implement multiple parents, verify passthrough creation for non-adjacent parents
  7. Test 7 → Verify passthrough + fork interaction (should pass)
  8. Test 8 → Full integration test of the algorithm
  9. Test 9 → Edge case with many parallel passthroughs
 */


private val A = ChangeId("A")
private val B = ChangeId("B")
private val C = ChangeId("C")
private val D = ChangeId("D")
private val E = ChangeId("E")
private val F = ChangeId("F")
private val G = ChangeId("G")

class LayoutCalculatorTest {
    private val calculator = LayoutCalculatorImpl()

    // 1. Single entry - baseline
    @Test
    fun `single entry with no parents is assigned lane 0`() {
        val entries = listOf(GraphEntry(A, emptyList()))
        val layout = calculator.calculate(entries)

        layout.rows[0].lane shouldBe 0
        layout.rows[0].childLanes shouldBe emptyList()
        layout.rows[0].parentLanes shouldBe emptyList()
        layout.rows[0].passthroughLanes shouldBe emptySet()
    }

    // 2. Linear parent-child
    @Test
    fun `two entries in parent-child relationship use same lane`() {
        val entries = listOf(
            GraphEntry(A, listOf(B)),
            GraphEntry(B, emptyList())
        )
        val layout = calculator.calculate(entries)

        layout.rows[0].lane shouldBe 0
        layout.rows[0].parentLanes shouldBe listOf(0)  // B is at lane 0
        layout.rows[1].lane shouldBe 0
        layout.rows[1].childLanes shouldBe listOf(0)   // A is at lane 0
    }

    // 3. Linear chain
    @Test
    fun `linear chain of three entries all use lane 0`() {
        val entries = listOf(
            GraphEntry(A, listOf(B)),
            GraphEntry(B, listOf(C)),
            GraphEntry(C, emptyList())
        )
        val layout = calculator.calculate(entries)

        layout.rows.size shouldBe 3
        layout.rows.forEach { it.lane shouldBe 0 }
    }

    // 4. Gap creates passthrough
    @Test
    fun `parent two rows below creates passthrough in middle row`() {
        val entries = listOf(
            GraphEntry(A, listOf(C)),
            GraphEntry(B, emptyList()),  // unrelated
            GraphEntry(C, emptyList())
        )
        val layout = calculator.calculate(entries)

        layout.rows[0].lane shouldBe 0
        layout.rows[1].lane shouldBe 1           // B pushed to lane 1
        layout.rows[1].passthroughLanes shouldBe listOf(0)  // passthrough at lane 0
        layout.rows[2].lane shouldBe 0           // C at lane 0
    }

    // 5. Simple fork
    @Test
    fun `fork - two children share one parent`() {
        val entries = listOf(
            GraphEntry(A, listOf(C)),
            GraphEntry(B, listOf(C)),
            GraphEntry(C, emptyList())
        )
        val layout = calculator.calculate(entries)

        layout.rows[0].lane shouldBe 0           // A at lane 0
        layout.rows[1].lane shouldBe 1           // B at lane 1 (0 has passthrough)
        layout.rows[2].lane shouldBe 0           // C at lane 0 (lowest child lane)
        layout.rows[2].childLanes shouldContainExactlyInAnyOrder listOf(0, 1)
    }

    // 6. Simple merge
    // Passthrough to non-adjacent parent blocks child's lane.
    // Adjacent parent is pushed to a new lane. Non-adjacent parent gets child's lane.
    @Test
    fun `merge - one child has two parents`() {
        val entries = listOf(
            GraphEntry(A, listOf(B, C)),
            GraphEntry(B, emptyList()),
            GraphEntry(C, emptyList())
        )
        val layout = calculator.calculate(entries)

        layout.rows[0].lane shouldBe 0
        layout.rows[0].parentLanes shouldContainExactlyInAnyOrder listOf(1, 0)
        layout.rows[1].lane shouldBe 1  // B (first parent) pushed to lane 1 by passthrough to C
        layout.rows[2].lane shouldBe 0  // C (second parent) at lane 0 (passthrough terminates)
    }

    // 7. Fork with passthrough
    @Test
    fun `fork where first child skips a row creates passthrough`() {
        val entries = listOf(
            GraphEntry(A, listOf(C)),  // A's parent is C (skips B)
            GraphEntry(B, listOf(C)),  // B's parent is C (adjacent)
            GraphEntry(C, emptyList())
        )
        val layout = calculator.calculate(entries)

        // Same as test 5, validates passthrough handling
        layout.rows[1].passthroughLanes shouldBe listOf(0)
    }

    // 8. Diamond pattern (fork + merge)
    // A merges B and E. Passthrough to E blocks lane 0, so B branch goes to lane 1.
    // Main line continues: A → E → F → G in lane 0.
    // Side branch: B → C → D in lane 1.
    @Test
    fun `diamond pattern - fork at bottom, merge at top`() {
        val entries = listOf(
            GraphEntry(A, listOf(B, E)),  // merge
            GraphEntry(B, listOf(C)),
            GraphEntry(C, listOf(D)),
            GraphEntry(D, listOf(G)),
            GraphEntry(E, listOf(F)),
            GraphEntry(F, listOf(G)),
            GraphEntry(G, emptyList())        // fork point
        )
        val layout = calculator.calculate(entries)

        // Verify lane assignments
        // Passthrough A→E at lane 0 pushes B branch to lane 1
        layout.rows[0].lane shouldBe 0  // A
        layout.rows[1].lane shouldBe 1  // B (pushed by passthrough to E)
        layout.rows[2].lane shouldBe 1  // C (follows B)
        layout.rows[3].lane shouldBe 1  // D (follows C)
        layout.rows[4].lane shouldBe 0  // E (passthrough terminates, gets child lane)
        layout.rows[5].lane shouldBe 0  // F (follows E)
        layout.rows[6].lane shouldBe 0  // G (lowest child lane)

        // Verify passthroughs
        layout.rows[1].passthroughLanes shouldBe setOf(0)  // A→E at lane 0 (child's lane)
        layout.rows[2].passthroughLanes shouldBe setOf(0)  // A→E at lane 0
        layout.rows[3].passthroughLanes shouldBe setOf(0)  // A→E at lane 0
        layout.rows[4].passthroughLanes shouldBe setOf(1)  // D→G at lane 1 (D's lane)
        layout.rows[5].passthroughLanes shouldBe setOf(1)  // D→G at lane 1
    }

    // 9. Multiple independent branches
    @Test
    fun `multiple independent branches with passthroughs`() {
        val entries = listOf(
            GraphEntry(A, listOf(D)),
            GraphEntry(B, listOf(E)),
            GraphEntry(C, listOf(F)),
            GraphEntry(D, emptyList()),
            GraphEntry(E, emptyList()),
            GraphEntry(F, emptyList())
        )
        val layout = calculator.calculate(entries)

        // Each branch gets its own lane
        layout.rows[0].lane shouldBe 0  // A
        layout.rows[1].lane shouldBe 1  // B
        layout.rows[2].lane shouldBe 2  // C

        // Passthroughs accumulate
        layout.rows[1].passthroughLanes shouldBe listOf(0)
        layout.rows[2].passthroughLanes shouldBe listOf(0, 1)
    }
}
