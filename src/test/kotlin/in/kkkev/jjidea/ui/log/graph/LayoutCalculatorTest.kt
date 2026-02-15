package `in`.kkkev.jjidea.ui.log.graph

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

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

private const val A = "A"
private const val B = "B"
private const val C = "C"
private const val D = "D"
private const val E = "E"
private const val F = "F"
private const val G = "G"

class LayoutCalculatorTest {
    private val calculator = LayoutCalculatorImpl<String>()

    // 1. Single entry - baseline
    @Test
    fun `single entry with no parents is assigned lane 0`() {
        val entries = listOf(GraphEntry(A, emptyList()))
        val layout = calculator.calculate(entries)

        layout.rows[0].lane shouldBe 0
        layout.rows[0].childLanes shouldBe emptyList()
        layout.rows[0].parentLanes shouldBe emptyList()
        layout.rows[0].passthroughLanes shouldBe emptyMap()
    }

    // 2. Linear parent-child
    @Test
    fun `two entries in parent-child relationship use same lane`() {
        val entries =
            listOf(
                GraphEntry(A, listOf(B)),
                GraphEntry(B, emptyList())
            )
        val layout = calculator.calculate(entries)

        layout.rows[0].lane shouldBe 0
        layout.rows[0].parentLanes shouldBe listOf(0) // B is at lane 0
        layout.rows[1].lane shouldBe 0
        layout.rows[1].childLanes shouldBe listOf(0) // A is at lane 0
    }

    // 3. Linear chain
    @Test
    fun `linear chain of three entries all use lane 0`() {
        val entries =
            listOf(
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
        val entries =
            listOf(
                GraphEntry(A, listOf(C)),
                GraphEntry(B, emptyList()), // unrelated
                GraphEntry(C, emptyList())
            )
        val layout = calculator.calculate(entries)

        layout.rows[0].lane shouldBe 0
        layout.rows[0].passthroughLanes shouldBe mapOf(C to 0) // A→C passthrough at lane 0
        layout.rows[1].lane shouldBe 1 // B pushed to lane 1
        layout.rows[1].passthroughLanes shouldBe emptyMap() // B has no non-adjacent parents
        layout.rows[2].lane shouldBe 0 // C at lane 0
    }

    // 5. Simple fork
    @Test
    fun `fork - two children share one parent`() {
        val entries =
            listOf(
                GraphEntry(A, listOf(C)),
                GraphEntry(B, listOf(C)),
                GraphEntry(C, emptyList())
            )
        val layout = calculator.calculate(entries)

        layout.rows[0].lane shouldBe 0 // A at lane 0
        layout.rows[1].lane shouldBe 1 // B at lane 1 (0 has passthrough)
        layout.rows[2].lane shouldBe 0 // C at lane 0 (lowest child lane)
        layout.rows[2].childLanes shouldContainExactlyInAnyOrder listOf(0, 1)
    }

    // 6. Simple merge (pure merge case)
    // A has two parents B and C. Neither parent has other children, so both are "pure merge".
    // Adjacent parent B is not blocked (passthrough to C uses new lane, not child's lane).
    // Non-adjacent parent C gets the reserved new lane.
    @Test
    fun `merge - one child has two parents`() {
        val entries =
            listOf(
                GraphEntry(A, listOf(B, C)),
                GraphEntry(B, emptyList()),
                GraphEntry(C, emptyList())
            )
        val layout = calculator.calculate(entries)

        layout.rows[0].lane shouldBe 0
        layout.rows[0].parentLanes shouldContainExactlyInAnyOrder listOf(0, 1)
        layout.rows[1].lane shouldBe 0 // B can use lane 0 (passthrough to C is at lane 1)
        layout.rows[2].lane shouldBe 1 // C at lane 1 (reserved by pure merge passthrough)
    }

    // 7. Fork with passthrough
    @Test
    fun `fork where first child skips a row creates passthrough`() {
        val entries =
            listOf(
                GraphEntry(A, listOf(C)), // A's parent is C (skips B)
                GraphEntry(B, listOf(C)), // B's parent is C (adjacent)
                GraphEntry(C, emptyList())
            )
        val layout = calculator.calculate(entries)

        // A→C passthrough at lane 0 (A's lane, fork connection)
        layout.rows[0].passthroughLanes shouldBe mapOf(C to 0)
        layout.rows[1].passthroughLanes shouldBe emptyMap() // B→C is adjacent
    }

    // 8. Diamond pattern (fork + merge)
    // A merges B and E. E is a pure merge (no other children) so passthrough to E
    // goes to lane 1, NOT blocking lane 0. B branch continues in lane 0.
    // D→G passthrough uses lane 0 (D has 1 parent, not a merge).
    @Test
    fun `diamond pattern - fork at bottom, merge at top`() {
        val entries =
            listOf(
                GraphEntry(A, listOf(B, E)), // merge
                GraphEntry(B, listOf(C)),
                GraphEntry(C, listOf(D)),
                GraphEntry(D, listOf(G)),
                GraphEntry(E, listOf(F)),
                GraphEntry(F, listOf(G)),
                GraphEntry(G, emptyList()) // fork point
            )
        val layout = calculator.calculate(entries)

        // Verify lane assignments
        // E is pure merge, so passthrough at lane 1 (reserved for E)
        // B branch not blocked, continues in lane 0
        layout.rows[0].lane shouldBe 0 // A
        layout.rows[1].lane shouldBe 0 // B (not blocked, passthrough to E is at lane 1)
        layout.rows[2].lane shouldBe 0 // C (follows B)
        layout.rows[3].lane shouldBe 0 // D (follows C)
        layout.rows[4].lane shouldBe 1 // E (reserved lane from pure merge)
        layout.rows[5].lane shouldBe 1 // F (follows E)
        layout.rows[6].lane shouldBe 0 // G (lowest child lane)

        // Verify per-entry passthroughs
        layout.rows[0].passthroughLanes shouldBe mapOf(E to 1) // A→E pure merge at lane 1
        layout.rows[1].passthroughLanes shouldBe emptyMap() // B→C adjacent
        layout.rows[2].passthroughLanes shouldBe emptyMap() // C→D adjacent
        layout.rows[3].passthroughLanes shouldBe mapOf(G to 0) // D→G at lane 0
        layout.rows[4].passthroughLanes shouldBe emptyMap() // E→F adjacent
        layout.rows[5].passthroughLanes shouldBe emptyMap() // F→G adjacent
    }

    // 9. Multiple independent branches
    @Test
    fun `multiple independent branches with passthroughs`() {
        val entries =
            listOf(
                GraphEntry(A, listOf(D)),
                GraphEntry(B, listOf(E)),
                GraphEntry(C, listOf(F)),
                GraphEntry(D, emptyList()),
                GraphEntry(E, emptyList()),
                GraphEntry(F, emptyList())
            )
        val layout = calculator.calculate(entries)

        // Each branch gets its own lane
        layout.rows[0].lane shouldBe 0 // A
        layout.rows[1].lane shouldBe 1 // B
        layout.rows[2].lane shouldBe 2 // C

        // Per-entry passthroughs
        layout.rows[0].passthroughLanes shouldBe mapOf(D to 0) // A→D at lane 0
        layout.rows[1].passthroughLanes shouldBe mapOf(E to 1) // B→E at lane 1
        layout.rows[2].passthroughLanes shouldBe mapOf(F to 2) // C→F at lane 2
    }

    // 10. Fork+Merge classification
    // When a connection is both a fork (parent has multiple children) AND a merge
    // (child has multiple parents), treat it as a fork: passthrough stays in child's lane.
    // Pure merges (parent has only one child) get a NEW lane.
    //
    // Scenario:
    //   Row 0: A (zks) → D (ozu)         - creates passthrough in lane 0
    //   Row 1: B (zqkz) → D (ozu), E (nwu) - merge commit
    //   Row 2: C (intermediate)           - unrelated
    //   Row 3: D (ozu)                    - fork point (children: A, B)
    //   Row 4: E (nwu)                    - only child: B
    //
    // Connection classification:
    //   A→D: simple (A has 1 parent, D will have 2 children but we see A first)
    //   B→D: fork+merge (D already has child A, B has multiple parents)
    //   B→E: pure merge (E has no other children, B has multiple parents)
    //
    // Expected passthrough lanes:
    //   A→D: lane 0 (A's lane)
    //   B→D: lane 1 (B's lane, fork+merge → child's lane)
    //   B→E: lane 2 (NEW lane, pure merge → new lane, E inherits it)
    @Test
    fun `fork+merge uses child lane, pure merge uses new lane`() {
        val entries =
            listOf(
                GraphEntry(A, listOf(D)), // Row 0: A → D
                GraphEntry(B, listOf(D, E)), // Row 1: B → D, E (merge)
                GraphEntry(C, emptyList()), // Row 2: C (unrelated)
                GraphEntry(D, emptyList()), // Row 3: D (fork point)
                GraphEntry(E, emptyList()) // Row 4: E
            )
        val layout = calculator.calculate(entries)

        // Lane assignments
        layout.rows[0].lane shouldBe 0 // A at lane 0
        layout.rows[0].passthroughLanes shouldBe mapOf(D to 0) // A→D at lane 0
        layout.rows[1].lane shouldBe 1 // B blocked by A→D passthrough, takes lane 1
        layout.rows[3].lane shouldBe 0 // D at lane 0 (lowest child lane: A)
        layout.rows[4].lane shouldBe 2 // E at lane 2 (reserved by pure merge passthrough)

        // C has no parents, no passthroughs
        layout.rows[2].passthroughLanes shouldBe emptyMap()

        // C is pushed to lane 3 (lanes 0, 1, 2 all blocked by passthroughs)
        layout.rows[2].lane shouldBe 3

        // Verify parent lanes for B
        layout.rows[1].parentLanes shouldContainExactlyInAnyOrder listOf(0, 2) // D at 0, E at 2

        // Verify passthrough lane assignments per parent (fixes phantom passthrough bug)
        // B→D is fork+merge: passthrough at child's lane (1)
        // B→E is pure merge: passthrough at new lane (2)
        layout.rows[1].passthroughLanes[D] shouldBe 1
        layout.rows[1].passthroughLanes[E] shouldBe 2
    }

    // 11. Reserved lane release
    // When an entry uses its reserved lane, that lane should be available for new reservations.
    //
    // Scenario (from bug report):
    //   Row 0: pp → ou, xs (merge)
    //   Row 1: ou → qn
    //   Row 2: ut → qn
    //   Row 3: xs → qn
    //   Row 4: qn → rso, oq (merge)
    //   Row 5: rso
    //   Row 6: oq
    //
    // pp→xs creates passthrough at lane 1 (pure merge, reserved for xs)
    // When xs is processed at row 3, it uses lane 1 and the reservation should be cleared.
    // When qn creates passthrough for oq, lane 1 should be available.
    @Test
    fun `reserved lane is released when used, allowing reuse`() {
        val pp = "pp"
        val ou = "ou"
        val ut = "ut"
        val xs = "xs"
        val qn = "qn"
        val rso = "rso"
        val oq = "oq"

        val entries = listOf(
            GraphEntry(pp, listOf(ou, xs)), // Row 0: merge
            GraphEntry(ou, listOf(qn)), // Row 1
            GraphEntry(ut, listOf(qn)), // Row 2
            GraphEntry(xs, listOf(qn)), // Row 3
            GraphEntry(qn, listOf(rso, oq)), // Row 4: merge
            GraphEntry(rso, emptyList()), // Row 5
            GraphEntry(oq, emptyList()) // Row 6
        )
        val layout = calculator.calculate(entries)

        // pp at lane 0, xs reserved for lane 1
        layout.rows[0].lane shouldBe 0 // pp
        layout.rows[3].lane shouldBe 1 // xs uses reserved lane 1

        // When qn creates passthrough to oq, lane 1 should now be available
        // oq should get lane 1, not lane 2
        layout.rows[6].lane shouldBe 1 // oq at lane 1 (reservation was cleared)
    }

    // 12. Double merge scenario from jj-idea repo
    // Structure: lur→(yyu,rnz), then later uxy→(vuw,uyy)
    // Both rnz and uyy should use reserved lanes (pure merge)
    @Test
    fun `double merge scenario - nested pure merges`() {
        val lur = "lur"
        val yyu = "yyu"
        val rnz = "rnz"
        val ysy = "ysy"
        val lts = "lts"
        val rsu = "rsu"
        val uxoy = "uxoy"
        val uxy = "uxy"
        val vuw = "vuw"
        val uyy = "uyy"
        val kpv = "kpv"
        val kry = "kry"

        val entries = listOf(
            GraphEntry(lur, listOf(yyu, rnz)), // Row 0: merge
            GraphEntry(yyu, listOf(ysy)), // Row 1
            GraphEntry(rnz, listOf(ysy)), // Row 2
            GraphEntry(ysy, listOf(lts)), // Row 3
            GraphEntry(lts, listOf(rsu)), // Row 4
            GraphEntry(rsu, listOf(uxoy)), // Row 5
            GraphEntry(uxoy, listOf(uxy)), // Row 6
            GraphEntry(uxy, listOf(vuw, uyy)), // Row 7: second merge
            GraphEntry(vuw, listOf(kpv)), // Row 8
            GraphEntry(uyy, listOf(kpv)), // Row 9
            GraphEntry(kpv, listOf(kry)), // Row 10
            GraphEntry(kry, emptyList()) // Row 11
        )
        val layout = calculator.calculate(entries)

        // First merge: lur@0, rnz gets reserved lane 1
        layout.rows[0].lane shouldBe 0 // lur
        layout.rows[1].lane shouldBe 0 // yyu (not blocked, passthrough to rnz at lane 1)
        layout.rows[2].lane shouldBe 1 // rnz (uses reserved lane)

        // Linear section
        layout.rows[3].lane shouldBe 0 // ysy
        layout.rows[6].lane shouldBe 0 // uxoy

        // Second merge: uxy@0, uyy gets reserved lane 1
        layout.rows[7].lane shouldBe 0 // uxy
        layout.rows[8].lane shouldBe 0 // vuw (should NOT be blocked)
        layout.rows[9].lane shouldBe 1 // uyy (uses reserved lane, NOT lane 2)
        layout.rows[10].lane shouldBe 0 // kpv
    }

    // 13. Same scenario with working copy at top
    @Test
    fun `double merge scenario with working copy - lanes should be same as without`() {
        val txq = "txq" // Working copy
        val lur = "lur"
        val yyu = "yyu"
        val rnz = "rnz"
        val ysy = "ysy"
        val lts = "lts"
        val rsu = "rsu"
        val uxoy = "uxoy"
        val uxy = "uxy"
        val vuw = "vuw"
        val uyy = "uyy"
        val kpv = "kpv"
        val kry = "kry"

        val entries = listOf(
            GraphEntry(txq, listOf(lur)), // Row 0: working copy
            GraphEntry(lur, listOf(yyu, rnz)), // Row 1: merge
            GraphEntry(yyu, listOf(ysy)), // Row 2
            GraphEntry(rnz, listOf(ysy)), // Row 3
            GraphEntry(ysy, listOf(lts)), // Row 4
            GraphEntry(lts, listOf(rsu)), // Row 5
            GraphEntry(rsu, listOf(uxoy)), // Row 6
            GraphEntry(uxoy, listOf(uxy)), // Row 7
            GraphEntry(uxy, listOf(vuw, uyy)), // Row 8: second merge
            GraphEntry(vuw, listOf(kpv)), // Row 9
            GraphEntry(uyy, listOf(kpv)), // Row 10
            GraphEntry(kpv, listOf(kry)), // Row 11
            GraphEntry(kry, emptyList()) // Row 12
        )
        val layout = calculator.calculate(entries)

        // All linear commits stay in lane 0
        layout.rows[0].lane shouldBe 0 // txq
        layout.rows[1].lane shouldBe 0 // lur

        // First merge: rnz gets reserved lane 1
        layout.rows[2].lane shouldBe 0 // yyu
        layout.rows[3].lane shouldBe 1 // rnz

        // Linear section
        layout.rows[4].lane shouldBe 0 // ysy
        layout.rows[7].lane shouldBe 0 // uxoy

        // Second merge: uxy@0, uyy gets reserved lane 1
        layout.rows[8].lane shouldBe 0 // uxy
        layout.rows[9].lane shouldBe 0 // vuw (should NOT be blocked!)
        layout.rows[10].lane shouldBe 1 // uyy (reserved lane)
        layout.rows[11].lane shouldBe 0 // kpv

        // Print actual values for debugging
        println("=== Actual lane assignments ===")
        entries.forEachIndexed { idx, entry ->
            println(
                "Row $idx (${entry.current}): lane ${layout.rows[idx].lane}, " +
                    "parentLanes=${layout.rows[idx].parentLanes}, " +
                    "passthroughs=${layout.rows[idx].passthroughLanes}"
            )
        }
    }

    // 14. Merge with intervening side branch makes both parents non-adjacent
    //
    // When a side branch (S) appears between a merge commit (M) and its parents,
    // both parents become non-adjacent. Without the fix, both would get NEW lanes.
    // With the fix, the first parent uses the child's lane.
    //
    // Structure:
    //   M → P1, P2  (merge)
    //   S → P2      (side branch - makes P1 non-adjacent!)
    //   P1
    //   P2
    @Test
    fun `merge with intervening side branch - first parent keeps child lane`() {
        val m = "M"
        val s = "S"
        val p1 = "P1"
        val p2 = "P2"

        val entries = listOf(
            GraphEntry(m, listOf(p1, p2)), // Row 0: merge
            GraphEntry(s, listOf(p2)), // Row 1: side branch makes P1 non-adjacent!
            GraphEntry(p1, emptyList()), // Row 2
            GraphEntry(p2, emptyList()) // Row 3
        )
        val layout = calculator.calculate(entries)

        // M at lane 0, first parent P1 uses child's lane (0), second parent P2 gets lane 1
        layout.rows[0].lane shouldBe 0 // M
        layout.rows[1].lane shouldBe 2 // S blocked by passthroughs at 0 and 1
        layout.rows[2].lane shouldBe 0 // P1 - first parent keeps child's lane
        layout.rows[3].lane shouldBe 1 // P2 - second parent gets new lane
    }
}
