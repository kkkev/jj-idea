package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.log.graph.ParentState
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Unit tests for [GraphEdgeIndex] (jj-idea-sc8m): pure, font-free coverage of the row/lane edge
 * lookup, its lane-x hit test, and the long-edge hover classification, without needing a real
 * [JujutsuLogTable]/[javax.swing.JTable] - see [JujutsuLogTableBookmarkClickTest] for the platform
 * mouse-event coverage of the wiring on top of this.
 */
class GraphEdgeIndexTest {
    private val repo = mockk<JujutsuRepository>()

    private fun entry(id: String, parentIds: List<String> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "commit $id",
        parentIds = parentIds.map { ChangeId(it, it, null) }
    )

    private fun key(id: String) = ChangeKey(repo, ChangeId(id, id, null))

    private fun buildIndex(entries: List<LogEntry>, allEntries: List<LogEntry> = entries): GraphEdgeIndex {
        val nodes = CommitGraphBuilder().buildGraph(entries, allEntries)
        return GraphEdgeIndex.build(entries, nodes)
    }

    @Test
    fun `linear chain resolves the first row's edge to its adjacent parent`() {
        val a = entry("a", listOf("b"))
        val b = entry("b", listOf("c"))
        val c = entry("c")
        val index = buildIndex(listOf(a, b, c))

        // Row 0 is unambiguous - it's the only edge touching that row at all.
        index.edgeAt(0, 0) shouldBe GraphEdge(key("a"), key("b"), null)
        // Row 2 is the root, but its cell still paints the incoming connector from b arriving at
        // c's circle (the line's top half) - so it still resolves to b->c, not null.
        index.edgeAt(2, 0) shouldBe GraphEdge(key("b"), key("c"), null)
        // Row 1 sits at the junction of a->b (ending there) and b->c (starting there) - both are
        // one-row spans in the same lane, a visually continuous line either way; which one wins
        // the tie is covered by the dedicated collision test below.
        index.edgeAt(1, 0).shouldNotBeNull()
    }

    @Test
    fun `a merge commit's own row does not get a spurious passthrough line in its sibling parent's lane`() {
        // m merges p0 (mainline - shares m's lane, adjacent) with p1 (a later pure-merge sibling,
        // which gets its own distinct lane reserved just for it, jj-idea-sc8m regression: the
        // pre-fix `!= ownLane` filter didn't exclude m's own row from that sibling lane, so the
        // renderer drew a spurious extra vertical line there alongside the real diagonal.
        val m = entry("m", listOf("p0", "p1"))
        val p0 = entry("p0")
        val p1 = entry("p1")
        val index = buildIndex(listOf(m, p0, p1))
        val mNode = CommitGraphBuilder().buildGraph(listOf(m, p0, p1)).getValue(key("m"))
        val siblingLane = mNode.passthroughLanes.getValue(key("p1"))

        // The sibling connector is still a real, hit-testable edge at m's own row...
        index.edgeAt(0, siblingLane) shouldBe GraphEdge(key("m"), key("p1"), null)
        // ...but must not also be reported as a passthrough lane there - that's what the plain
        // full-height vertical line painter (drawPassThroughLines) reads, and m's own row paints
        // that connector as a diagonal, not a passthrough.
        index.passthroughLanes(0) shouldNotContain siblingLane
        // Row 1 (p0, strictly between m and p1) genuinely is a passthrough row for this connector -
        // a plain vertical line there is correct, unlike at the two endpoint rows.
        index.passthroughLanes(1) shouldContain siblingLane
    }

    @Test
    fun `off-lane and unoccupied-lane hits miss`() {
        val a = entry("a", listOf("b"))
        val b = entry("b")
        val index = buildIndex(listOf(a, b))

        index.edgeAt(0, 1).shouldBeNull()
        index.edgeAt(5, 0).shouldBeNull()
    }

    @Test
    fun `a passthrough lane resolves to the same edge at every row it spans`() {
        // a -> c, skipping b entirely (non-adjacent parent) - opens a passthrough through b's row.
        val a = entry("a", listOf("c"))
        val b = entry("b")
        val c = entry("c")
        val index = buildIndex(listOf(a, b, c))

        val edge = GraphEdge(key("a"), key("c"), null)
        index.edgeAt(0, 0) shouldBe edge
        index.edgeAt(1, 0) shouldBe edge // passthrough row
        index.edgeAt(2, 0) shouldBe edge
    }

    @Test
    fun `when two edges compete for the same row-lane the longer-spanning edge wins`() {
        // a -> d is a 3-row passthrough in lane 0 (skipping b, c). d's lane becomes free again at
        // d's own row once the passthrough terminates there, and d's own (adjacent, 1-row) edge to
        // e naturally reuses that same lane 0 at that same row - a real (row, lane) collision
        // between a 3-row edge and a 1-row edge. The long one must win.
        val a = entry("a", listOf("d"))
        val b = entry("b")
        val c = entry("c")
        val d = entry("d", listOf("e"))
        val e = entry("e")
        val index = buildIndex(listOf(a, b, c, d, e))

        index.edgeAt(3, 0) shouldBe GraphEdge(key("a"), key("d"), null)
    }

    @Test
    fun `stub edge is recorded for an unresolved parent`() {
        val a = entry("a", listOf("missing"))
        val index = buildIndex(listOf(a))

        val edge = index.edgeAt(0, 0)
        edge.shouldNotBeNull()
        edge.child shouldBe key("a")
        edge.parent shouldBe key("missing")
        edge.state shouldBe ParentState.NOT_LOADED
    }

    @Test
    fun `stub edge is HIDDEN when the parent is loaded but filtered out`() {
        val a = entry("a", listOf("hidden"))
        val hidden = entry("hidden")
        // "a" alone is laid out; "hidden" is in allEntries but not in the visible set.
        val index = buildIndex(listOf(a), allEntries = listOf(a, hidden))

        index.edgeAt(0, 0)?.state shouldBe ParentState.HIDDEN
    }

    @Test
    fun `laneAt maps x to a lane, or null left of the graph`() {
        val a = entry("a")
        val index = buildIndex(listOf(a))

        index.laneAt(localX = 3, startX = 4, laneWidth = 16).shouldBeNull()
        index.laneAt(localX = 4, startX = 4, laneWidth = 16) shouldBe 0
        index.laneAt(localX = 20, startX = 4, laneWidth = 16) shouldBe 1
    }

    @Test
    fun `rowOf finds a laid-out key and null for one not in the layout`() {
        val a = entry("a", listOf("b"))
        val b = entry("b")
        val index = buildIndex(listOf(a, b))

        index.rowOf(key("a")) shouldBe 0
        index.rowOf(key("b")) shouldBe 1
        index.rowOf(key("nope")).shouldBeNull()
    }

    @Test
    fun `not long when both ends are inside the viewport`() {
        val a = entry("a", listOf("b"))
        val b = entry("b")
        val index = buildIndex(listOf(a, b))

        index.hoveredEdgeAt(row = 0, lane = 0, visibleRows = 0..1).shouldBeNull()
    }

    @Test
    fun `direction always points away from an already-visible end - down when the child is visible`() {
        val a = entry("a", listOf("b"))
        val b = entry("b")
        val index = buildIndex(listOf(a, b))

        // Hovering the child's own row (0, visible) still points DOWN, towards the invisible
        // parent - there is nothing to choose since the child needs no navigating to.
        val hovered = index.hoveredEdgeAt(row = 0, lane = 0, visibleRows = 0..0)
        hovered.shouldNotBeNull()
        hovered.direction shouldBe EdgeDirection.DOWN
        hovered.targetKey shouldBe key("b")
        hovered.navigable shouldBe true
    }

    @Test
    fun `direction always points away from an already-visible end - up when the parent is visible`() {
        val a = entry("a", listOf("b"))
        val b = entry("b")
        val index = buildIndex(listOf(a, b))

        val hovered = index.hoveredEdgeAt(row = 1, lane = 0, visibleRows = 1..1)
        hovered.shouldNotBeNull()
        hovered.direction shouldBe EdgeDirection.UP
        hovered.targetKey shouldBe key("a")
    }

    @Test
    fun `when both ends are off-screen, direction pivots once at the viewport's middle row`() {
        // e0's parent is e19 directly (non-adjacent, a passthrough spanning every row in between) -
        // the filler entries e1..e18 have no relation to it at all, so lane 0 is occupied by this
        // one long edge for its whole span. Viewed through rows 5..15 (middle row 10), both e0
        // and e19 are off-screen - rows at or above the middle point up (towards the child); rows
        // below it point down (towards the parent) - one stable transition point, not a per-row
        // split (jj-idea-sc8m round 3).
        val filler = (1 until 19).map { i -> entry("e$i") }
        val entries = listOf(entry("e0", listOf("e19"))) + filler + listOf(entry("e19"))
        val index = buildIndex(entries)
        val visibleRows = 5..15

        index.hoveredEdgeAt(row = 5, lane = 0, visibleRows).let {
            it.shouldNotBeNull()
            it.direction shouldBe EdgeDirection.UP
            it.targetKey shouldBe key("e0")
        }
        index.hoveredEdgeAt(row = 10, lane = 0, visibleRows).let {
            it.shouldNotBeNull()
            it.direction shouldBe EdgeDirection.UP
        }
        index.hoveredEdgeAt(row = 11, lane = 0, visibleRows).let {
            it.shouldNotBeNull()
            it.direction shouldBe EdgeDirection.DOWN
            it.targetKey shouldBe key("e19")
        }
        index.hoveredEdgeAt(row = 15, lane = 0, visibleRows).let {
            it.shouldNotBeNull()
            it.direction shouldBe EdgeDirection.DOWN
        }
    }

    @Test
    fun `a NOT_LOADED stub is long, navigable, and always points down`() {
        val a = entry("a", listOf("missing"))
        val index = buildIndex(listOf(a))

        val hovered = index.hoveredEdgeAt(row = 0, lane = 0, visibleRows = 0..0)
        hovered.shouldNotBeNull()
        hovered.direction shouldBe EdgeDirection.DOWN
        hovered.navigable shouldBe true
        hovered.targetKey shouldBe key("missing")
    }

    @Test
    fun `a HIDDEN stub is long but not navigable`() {
        val a = entry("a", listOf("hidden"))
        val hidden = entry("hidden")
        val index = buildIndex(listOf(a), allEntries = listOf(a, hidden))

        val hovered = index.hoveredEdgeAt(row = 0, lane = 0, visibleRows = 0..0)
        hovered.shouldNotBeNull()
        hovered.navigable shouldBe false
    }

    @Test
    fun `hoveredEdgeAt returns null when there's no edge at that lane`() {
        val a = entry("a")
        val index = buildIndex(listOf(a))

        index.hoveredEdgeAt(row = 0, lane = 3, visibleRows = 0..0).shouldBeNull()
    }

    /**
     * Brute-force replica of the renderer's pre-jj-idea-a0wp `drawLinesToParents` - a plain
     * `0 until row` / `parentKeys` scan computing the same lane formula [GraphEdgeIndex.build]
     * now computes once and records both directions. Independent of [GraphEdgeIndex] itself (it
     * only reads [GraphNode]/[LogEntry] fields), so agreement with [GraphEdgeIndex.incomingEdges]/
     * [GraphEdgeIndex.outgoingEdges] pins jj-idea-a0wp's rewrite as a pure refactor, not just a
     * self-consistency check.
     */
    private fun bruteForceEdges(
        entries: List<LogEntry>,
        nodes: Map<ChangeKey, GraphNode>
    ): Pair<Map<Int, List<RowEdge>>, Map<Int, List<RowEdge>>> {
        val rowOfKey = entries.withIndex().associate { (row, e) -> e.key to row }
        val incoming = HashMap<Int, MutableList<RowEdge>>()
        val outgoing = HashMap<Int, MutableList<RowEdge>>()

        for ((row, entry) in entries.withIndex()) {
            val node = nodes[entry.key] ?: continue
            val childHasMultipleParents = node.parentLanes.size > 1
            for (parentKey in entry.parentKeys) {
                val parentNode = nodes[parentKey] ?: continue
                val parentRow = rowOfKey[parentKey] ?: continue
                val passThroughLane = node.passthroughLanes[parentKey]
                val lane = passThroughLane
                    ?: if (childHasMultipleParents && parentNode.lane != node.lane) parentNode.lane else node.lane
                val edge = GraphEdge(child = entry.key, parent = parentKey, state = null)
                outgoing.getOrPut(row) { mutableListOf() }.add(RowEdge(lane, edge))
                incoming.getOrPut(parentRow) { mutableListOf() }.add(RowEdge(lane, edge))
            }
        }
        return incoming to outgoing
    }

    private fun assertIncomingOutgoingMatchBruteForce(entries: List<LogEntry>, allEntries: List<LogEntry> = entries) {
        val nodes = CommitGraphBuilder().buildGraph(entries, allEntries)
        val index = GraphEdgeIndex.build(entries, nodes)
        val (incoming, outgoing) = bruteForceEdges(entries, nodes)

        entries.indices.forEach { row ->
            index.incomingEdges(row) shouldBe (incoming[row] ?: emptyList())
            index.outgoingEdges(row) shouldBe (outgoing[row] ?: emptyList())
        }
    }

    @Test
    fun `incoming and outgoing edges match a brute-force scan - linear chain`() {
        assertIncomingOutgoingMatchBruteForce(
            listOf(entry("a", listOf("b")), entry("b", listOf("c")), entry("c"))
        )
    }

    @Test
    fun `incoming and outgoing edges match a brute-force scan - fork (two children, one parent)`() {
        val p = entry("p")
        assertIncomingOutgoingMatchBruteForce(listOf(entry("a", listOf("p")), entry("b", listOf("p")), p))
    }

    @Test
    fun `incoming and outgoing edges match a brute-force scan - pure merge with a reordered second parent`() {
        // m's second parent (p1) sorts before its first (p0) in row order - exercises the
        // non-adjacent-first-parent / later-reserved-lane path.
        assertIncomingOutgoingMatchBruteForce(
            listOf(entry("m", listOf("p0", "p1")), entry("p1"), entry("filler"), entry("p0"))
        )
    }

    @Test
    fun `incoming and outgoing edges match a brute-force scan - fork+merge`() {
        // m merges p (which also has another child, c) - a Fork+Merge parent, not a Pure Merge.
        val p = entry("p")
        assertIncomingOutgoingMatchBruteForce(
            listOf(entry("m", listOf("p", "other")), entry("c", listOf("p")), p, entry("other"))
        )
    }

    @Test
    fun `incoming and outgoing edges match a brute-force scan - non-adjacent parent opens a passthrough`() {
        assertIncomingOutgoingMatchBruteForce(
            listOf(entry("a", listOf("d")), entry("b"), entry("c"), entry("d"))
        )
    }

    @Test
    fun `incoming and outgoing edges match a brute-force scan - mixed merge with a stub`() {
        assertIncomingOutgoingMatchBruteForce(
            entries = listOf(entry("m", listOf("p", "missing")), entry("p")),
            allEntries = listOf(entry("m", listOf("p", "missing")), entry("p"))
        )
    }

    @Test
    fun `emphasized marks the side of the pivot row towards the navigation target`() {
        // A pivot at row 5 pointing DOWN: rows at or below 5 (towards the parent) are emphasized,
        // rows above it (towards the child) are dimmed - and it flips for UP.
        val down = HoveredEdge(
            edge = GraphEdge(key("a"), key("b"), null),
            lane = 0,
            direction = EdgeDirection.DOWN,
            span = 0..10,
            pivotRow = 5,
            navigable = true
        )
        down.emphasized(5) shouldBe true
        down.emphasized(8) shouldBe true
        down.emphasized(4) shouldBe false

        val up = down.copy(direction = EdgeDirection.UP)
        up.emphasized(5) shouldBe true
        up.emphasized(4) shouldBe true
        up.emphasized(8) shouldBe false
    }
}
