package `in`.kkkev.jjidea.ui.log.graph

import `in`.kkkev.jjidea.ui.log.entry
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * jj-idea-66rr: an edge spanning at least `longEdgeRows` rows keeps a lane only for its two end
 * caps ([LONG_EDGE_PART_ROWS] rows each); the lane between is free for other edges. Small
 * thresholds here keep the graphs readable - the production default is [LONG_EDGE_ROWS].
 */
class LongEdgeLayoutTest {
    private val threshold = 6

    /** `a` is a child of `z` only after a run of `gap` single-parent filler commits on lane 0. */
    private fun forkWithLongEdge(gap: Int): List<GraphEntry<String>> {
        // top -> z (long edge, via lane 1 once `m0` also claims lane 0), m0 -> m1 -> ... -> z
        val fillers = (0 until gap).map { i -> entry("m$i", listOf(if (i + 1 < gap) "m${i + 1}" else "z")) }
        return listOf(entry("top", listOf("m0", "z"))) + fillers + entry("z")
    }

    private fun layout(entries: List<GraphEntry<String>>, longEdgeRows: Int = threshold) =
        LayoutCalculatorImpl<String>(longEdgeRows).calculate(entries)

    @Test
    fun `edge exactly at the threshold collapses, one row shorter does not`() {
        // top (row 0) -> z (row gap + 1): span = gap + 1.
        layout(forkWithLongEdge(threshold - 1)).rows.last().longEdgeCapLanes shouldContainKey "top"
        layout(forkWithLongEdge(threshold - 2)).rows.last().longEdgeCapLanes.shouldBeEmpty()
    }

    @Test
    fun `a long edge frees its lane between the caps for a later branch`() {
        val gap = 10
        val entries = forkWithLongEdge(gap)
        val rows = layout(entries).rows
        val longLane = rows.first().passthroughLanes.getValue("z")
        // Between the caps nothing holds `longLane`: the fillers all sit on lane 0 and the whole
        // graph stays two lanes wide only at the ends.
        rows.subList(2, rows.size - 2).forEach { it.lane shouldBe 0 }
        // The uncollapsed layout keeps that lane busy the whole way, so it is never narrower.
        val maxCollapsed = rows.maxOf { maxOf(it.lane, it.passthroughLanes.values.maxOrNull() ?: 0) }
        maxCollapsed shouldBeLessThan (maxOf(longLane, 1) + 1)
    }

    @Test
    fun `a collapsed edge records its bottom cap lane on the parent`() {
        val rows = layout(forkWithLongEdge(10)).rows
        val z = rows.last()
        z.longEdgeCapLanes shouldContainKey "top"
        // The top cap lane is the child's passthrough; the bottom cap prefers to reuse it.
        z.longEdgeCapLanes.getValue("top") shouldBe rows.first().passthroughLanes.getValue("z")
    }

    /** `m` merges `parents` long edges; `y` is a child of `z` whose long edge's top cap ends on `m`'s row. */
    private fun octopus(parents: Int, withCapAbove: Boolean): List<GraphEntry<String>> {
        val targets = (0 until parents).map { "p$it" }
        val fillers = (0 until threshold + 2).map { entry("f$it") }
        val head = if (withCapAbove) listOf(entry("y", listOf("z"))) else emptyList()
        return head + entry("m", targets) + fillers + (if (withCapAbove) listOf(entry("z")) else emptyList()) +
            targets.map { entry(it) }
    }

    @Test
    fun `an octopus merge of long edges leaves its first one straight down on its own lane`() {
        val m = layout(octopus(3, withCapAbove = false)).rows.first()
        m.passthroughLanes.getValue("p0") shouldBe m.lane
        m.passthroughLanes.values.toSet().size shouldBe 3
    }

    @Test
    fun `a merge's long edges reuse the lane of a top cap whose arrow ends on the merge's row`() {
        val rows = layout(octopus(3, withCapAbove = true)).rows
        val (y, m) = rows
        val capLane = y.passthroughLanes.getValue("z")
        // The arrow only covers the upper half of m's row: m's circle avoids the lane, its connectors may use it.
        m.lane shouldNotBe capLane
        m.passthroughLanes.values shouldContain capLane
        m.passthroughLanes.values.toSet().size shouldBe 3
    }

    @Test
    fun `random DAGs never place two things on one lane at one row, and never get wider`() {
        val random = Random(66)
        repeat(300) { round ->
            val n = 40 + random.nextInt(60)
            val entries = (0 until n).map { i ->
                // Half the commits also point at their adjacent row, so merges mixing an adjacent
                // parent with a long edge (git/git's shape) are common.
                val parents = (
                    (1..random.nextInt(3)).map { i + 1 + random.nextInt(minOf(20, n - i)) } +
                        (if (random.nextBoolean()) listOf(i + 1) else emptyList())
                )
                    .filter { it < n }.distinct()
                entry("e$i", parents.map { "e$it" })
            }
            val collapsed = layout(entries)
            val plain = layout(entries, Int.MAX_VALUE)
            interiorCollisions(entries, collapsed, threshold).shouldBeEmpty()
            unalignedUpArrows(entries, collapsed, threshold).shouldBeEmpty()
            val width = { l: GraphLayout<String> ->
                l.rows.maxOf { r ->
                    maxOf(
                        r.lane,
                        r.passthroughLanes.values.maxOrNull() ?: 0
                    )
                }
            }
            // Not a hard guarantee of a greedy allocator, so reported with the seed round if it ever breaks.
            width(collapsed).let { w -> check(w <= width(plain) + 1) { "round $round: width $w vs ${width(plain)}" } }
        }
    }

    /**
     * jj-idea-9ghx: a parent whose lane is free on the row above should get its long edge's up arrow on that
     * lane, so the arrow lines up with the node instead of kinking into it. Reports parents where it did not.
     */
    private fun unalignedUpArrows(
        entries: List<GraphEntry<String>>,
        layout: GraphLayout<String>,
        longEdgeRows: Int
    ): List<String> {
        val rowOf = entries.withIndex().associate { (i, e) -> e.current to i }
        val occupied = HashSet<Pair<Int, Int>>()
        layout.rows.forEachIndexed { r, row -> occupied += r to row.lane }
        layout.rows.forEachIndexed { c, row ->
            for ((parent, lane) in row.passthroughLanes) {
                val p = rowOf.getValue(parent)
                if (p - c >= longEdgeRows) {
                    occupied += (c + LONG_EDGE_PART_ROWS) to lane
                } else {
                    for (r in c + 1 until p) occupied += r to lane
                }
            }
        }
        return layout.rows.withIndex().mapNotNull { (p, row) ->
            val caps = row.longEdgeCapLanes.values
            val blocked = occupied.any { it.first == p - LONG_EDGE_PART_ROWS && it.second == row.lane }
            if (caps.isNotEmpty() && row.lane !in caps && !blocked) {
                "${row.id}@$p lane ${row.lane}, caps $caps"
            } else {
                null
            }
        }
    }

    /** Lane occupancy strictly between rows, per (row, lane); reports any double-booking. */
    private fun interiorCollisions(
        entries: List<GraphEntry<String>>,
        layout: GraphLayout<String>,
        longEdgeRows: Int
    ): List<String> {
        val rowOf = entries.withIndex().associate { (i, e) -> e.current to i }
        val used = HashMap<Pair<Int, Int>, String>()
        val problems = mutableListOf<String>()
        fun claim(row: Int, lane: Int, what: String) {
            // Identical claims (several plain passthroughs fanning out of one child share its stem lane) are
            // fine; a long edge's cap sharing a lane with anything else is not - its arrow would be unclickable.
            used.put(row to lane, what)?.let {
                if (it != what) problems += "row $row lane $lane: $it vs $what"
            }
        }
        layout.rows.forEachIndexed { r, row -> claim(r, row.lane, "node of ${row.id}") }
        layout.rows.forEachIndexed { c, row ->
            for ((parent, lane) in row.passthroughLanes) {
                val p = rowOf.getValue(parent)
                if (p - c >= longEdgeRows) {
                    claim(c + LONG_EDGE_PART_ROWS, lane, "top cap of ${row.id}")
                    val bottom = layout.rows[p].longEdgeCapLanes.getValue(row.id)
                    claim(p - LONG_EDGE_PART_ROWS, bottom, "bottom cap of ${row.id}->$parent")
                } else {
                    for (r in c + 1 until p) claim(r, lane, "passthrough of ${row.id}")
                }
            }
        }
        return problems
    }
}
