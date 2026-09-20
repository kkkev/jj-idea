package `in`.kkkev.jjidea.ui.log.graph

import `in`.kkkev.jjidea.ui.log.entry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Equivalence tests for [IncrementalLayout.append] (jj-idea-jnqi): pins the append-only
 * fast path against the always-correct from-scratch computation
 * (`LayoutCalculatorImpl.calculate` over the whole accumulated set), for both simple
 * page splits and the two correctness wrinkles documented on [IncrementalLayout] -
 * a later page resolving an earlier row's unresolved parent, and that resolution
 * retroactively changing a still-earlier row's cached `parentLanes`. Same brute-force
 * pattern as jj-idea-a0wp's `GraphEdgeIndexTest` equivalence tests.
 */
class IncrementalLayoutEquivalenceTest {
    /** Feeds [pages] one at a time to a fresh [IncrementalLayout] and returns the final layout. */
    private fun appendPages(pages: List<List<GraphEntry<String>>>): GraphLayout<String> {
        val engine = IncrementalLayout<String>()
        var layout = GraphLayout<String>(emptyList())
        for (page in pages) {
            layout = engine.append(page)
        }
        return layout
    }

    private fun assertEquivalent(pages: List<List<GraphEntry<String>>>) {
        val all = pages.flatten()
        val expected = LayoutCalculatorImpl<String>().calculate(all)
        appendPages(pages) shouldBe expected
    }

    @Test
    fun `single page matches a plain calculate`() {
        assertEquivalent(listOf(listOf(entry("A", listOf("B")), entry("B"))))
    }

    @Test
    fun `linear chain split across two pages`() {
        // e0 -> e1 -> ... -> e19, split after row 9 - every parent already loaded, no
        // unresolved-parent wrinkle, exercises the plain append path only.
        val all = (0 until 20).map { i -> entry("e$i", if (i + 1 < 20) listOf("e${i + 1}") else emptyList()) }
        assertEquivalent(listOf(all.subList(0, 10), all.subList(10, 20)))
    }

    @Test
    fun `wide fork-merge split across two pages`() {
        // A,B -> C -> D,E -> F (diamond-ish), split so the merge and the fork straddle
        // the page boundary.
        val all = listOf(
            entry("A", listOf("C")),
            entry("B", listOf("C")),
            entry("C", listOf("D", "E")),
            entry("D", listOf("F")),
            entry("E", listOf("F")),
            entry("F")
        )
        assertEquivalent(listOf(all.subList(0, 3), all.subList(3, 6)))
    }

    @Test
    fun `octopus merge split across three pages`() {
        val all = listOf(
            entry("A", listOf("D")),
            entry("B", listOf("D")),
            entry("C", listOf("D")),
            entry("D")
        )
        assertEquivalent(listOf(all.subList(0, 1), all.subList(1, 3), all.subList(3, 4)))
    }

    @Test
    fun `unresolved parent resolved by a later page (dead-end branch)`() {
        // A's parent Z isn't loaded in page 1 at all (true NOT_LOADED stub), then page 2
        // supplies it - the row referencing it is at the very start (row 0), forcing a
        // full-range resume.
        val page1 = listOf(entry("A", listOf("Z")), entry("B"))
        val page2 = listOf(entry("Z"))
        assertEquivalent(listOf(page1, page2))
    }

    @Test
    fun `unresolved parent resolved well past a checkpoint boundary triggers rewind`() {
        // Row 300 (D) references Z as an unresolved parent; Z arrives in page 2. The
        // checkpoint interval is 256, so this exercises resuming from a checkpoint
        // strictly before the triggering row, not from row 0 and not from row 300 itself.
        val before = (0 until 300).map { i -> entry("c$i", if (i + 1 < 300) listOf("c${i + 1}") else emptyList()) }
        val d = entry("D", listOf("Z"))
        val after = (301 until 600).map { i -> entry("d$i", if (i + 1 < 600) listOf("d${i + 1}") else emptyList()) }
        val page1 = before + d + after
        val page2 = listOf(entry("Z"))
        assertEquivalent(listOf(page1, page2))
    }

    @Test
    fun `resolving an unresolved parent retroactively patches an earlier child's stale parentLanes`() {
        // X is a stable early row whose parent P sits well after row 0. P's own parent Z
        // isn't loaded yet in page 1, so P gets no passthrough for it (dead end). Once Z
        // arrives in page 2 and P must reprocess, P's own lane changes (occupied by the
        // new passthrough opened at the earlier unresolved-turned-resolved point) -
        // X's cached parentLanes (pointing at P) must be repatched, not left stale.
        val x = entry("X", listOf("P"))
        val filler = (0 until 50).map { i -> entry("f$i", if (i + 1 < 50) listOf("f${i + 1}") else emptyList()) }
        // Opens a real passthrough once Z resolves, forcing P off its preferred lane.
        val blocker = entry("BLOCKER", listOf("Z"))
        val p = entry("P", listOf("Q"))
        val q = entry("Q")
        val page1 = listOf(x) + filler + listOf(blocker, p, q)
        val page2 = listOf(entry("Z"))
        assertEquivalent(listOf(page1, page2))
    }

    @Test
    fun `many sequential single-row pages match a plain calculate`() {
        val all = (0 until 50).map { i -> entry("e$i", if (i + 1 < 50) listOf("e${i + 1}") else emptyList()) }
        assertEquivalent(all.map { listOf(it) })
    }
}
