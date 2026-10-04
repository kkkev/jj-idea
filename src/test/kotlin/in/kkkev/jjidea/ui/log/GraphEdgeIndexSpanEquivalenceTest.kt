package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * jj-idea-2570.6: [GraphEdgeIndex] stores each edge once as a row interval on its lane instead of
 * one cell per (row, lane) it spans. This pins that as a pure refactor: [ReferenceCells] is the
 * previous per-cell `build`, kept verbatim as an oracle, and every query the index answers must
 * agree with it on randomized graphs (merges, octopus merges, unresolved parents, filtered subsets).
 */
class GraphEdgeIndexSpanEquivalenceTest {
    // Not a mockk: ChangeKey hashes its repo on every map op, and mockk records (with a stack trace)
    // every call - hundreds of thousands here, enough to exhaust the shared test JVM's heap.
    private val repo: JujutsuRepository = TODO_REPO

    private fun id(n: String) = ChangeId(n, n, null)

    private fun entry(n: String, parents: List<String>) = LogEntry(
        repo = repo,
        id = id(n),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "commit $n",
        parentIds = parents.map { id(it) }
    )

    /** The pre-2570.6 build: one map entry per (row, lane) each edge spans. */
    private class ReferenceCells(entries: List<GraphableEntry>, nodes: Map<ChangeKey, GraphNode>) {
        val rowOfKey = HashMap<ChangeKey, Int>()
        val edgesByRow = HashMap<Int, MutableMap<Int, GraphEdge>>()
        private val spanByRowLane = HashMap<Int, MutableMap<Int, Int>>()
        val ownLaneByRow = HashMap<Int, Int>()
        val rightmostLaneByRow = HashMap<Int, Int>()
        val passthroughLanesByRow: Map<Int, Set<Int>>

        private fun place(row: Int, lane: Int, edge: GraphEdge, span: Int) {
            val existingSpan = spanByRowLane.getOrPut(row) { mutableMapOf() }[lane]
            if (existingSpan != null && existingSpan >= span) return
            spanByRowLane[row]!![lane] = span
            edgesByRow.getOrPut(row) { mutableMapOf() }[lane] = edge
        }

        private fun markActive(row: Int, lane: Int) {
            rightmostLaneByRow[row] = maxOf(rightmostLaneByRow[row] ?: -1, lane)
        }

        init {
            entries.forEachIndexed { row, entry -> rowOfKey[entry.key] = row }
            for ((row, entry) in entries.withIndex()) {
                val key = entry.key
                val node = nodes[key] ?: continue
                ownLaneByRow[row] = node.lane
                markActive(row, node.lane)
                val childHasMultipleParents = node.parentLanes.size > 1
                for (parentKey in entry.parentKeys) {
                    val parentNode = nodes[parentKey] ?: continue
                    val parentRow = rowOfKey[parentKey] ?: continue
                    val lane = node.passthroughLanes[parentKey]
                        ?: if (childHasMultipleParents && parentNode.lane != node.lane) parentNode.lane else node.lane
                    val edge = GraphEdge(child = key, parent = parentKey, state = null)
                    val span = parentRow - row
                    for (r in row..parentRow) {
                        place(r, lane, edge, span)
                        markActive(r, lane)
                    }
                }
                stubTargetFor(node)?.let { (parentKey, state) ->
                    val stubLane = node.stubLane ?: node.lane
                    place(row, stubLane, GraphEdge(key, parentKey, state), span = 0)
                    markActive(row, stubLane)
                }
            }
            passthroughLanesByRow = edgesByRow.mapValues { (row, lanes) ->
                lanes.filterValues { edge ->
                    edge.state == null && rowOfKey[edge.child] != row && rowOfKey[edge.parent] != row
                }.keys
            }
        }
    }

    /** Topologically ordered random DAG; parents are always later rows. Some parents don't exist. */
    private fun randomEntries(random: Random, n: Int): List<LogEntry> = (0 until n).map { i ->
        val parentCount = when (random.nextInt(10)) {
            0 -> 0
            in 1..6 -> 1
            in 7..8 -> 2
            else -> 3
        }
        val parents = (0 until parentCount).mapNotNull {
            when {
                random.nextInt(12) == 0 -> "missing${random.nextInt(4)}"
                i + 1 < n -> "e${i + 1 + random.nextInt(minOf(n - i - 1, 25))}"
                else -> null
            }
        }.distinct()
        entry("e$i", parents)
    }

    private fun assertEquivalent(entries: List<LogEntry>, allEntries: List<LogEntry>) {
        val nodes = CommitGraphBuilder().buildGraph(entries, allEntries)
        val index = GraphEdgeIndex.build(entries, nodes)
        val reference = ReferenceCells(entries, nodes)
        val maxLane = nodes.values.maxOfOrNull { node ->
            maxOf(node.lane, node.stubLane ?: 0, node.passthroughLanes.values.maxOrNull() ?: 0)
        } ?: 0

        fun d(e: GraphEdge?) = e?.let { "${it.child.revision}->${it.parent.revision}/${it.state}" }
        val problems = mutableListOf<String>()
        for (row in entries.indices) {
            for (lane in 0..maxLane + 2) {
                val got = d(index.edgeAt(row, lane))
                val want = d(reference.edgesByRow[row]?.get(lane))
                if (got != want) problems += "edgeAt($row,$lane) got=$got want=$want"
            }
            val pt = index.passthroughLanes(row)
            val wantPt = reference.passthroughLanesByRow[row] ?: emptySet()
            if (pt != wantPt) problems += "passthrough($row) got=$pt want=$wantPt"
            val rm = index.rightmostLane(row)
            val wantRm = reference.rightmostLaneByRow[row] ?: (reference.ownLaneByRow[row] ?: 0)
            if (rm != wantRm) problems += "rightmost($row) got=$rm want=$wantRm"
        }
        // Compared as plain strings, not GraphEdge objects: an earlier version asserting on the edges
        // directly exhausted the test JVM's heap (cause not diagnosed), so keep failures cheap to render.
        problems shouldBe emptyList()
    }

    @Test
    fun `span index matches the per-cell reference on random graphs`() {
        repeat(40) { seed ->
            val random = Random(seed)
            val entries = randomEntries(random, 40 + random.nextInt(60))
            assertEquivalent(entries, entries)
        }
    }

    @Test
    fun `span index matches the per-cell reference on filtered subsets`() {
        repeat(40) { seed ->
            val random = Random(1000 + seed)
            val all = randomEntries(random, 60 + random.nextInt(40))
            val filtered = all.filter { random.nextInt(3) != 0 }
            if (filtered.isNotEmpty()) assertEquivalent(filtered, all)
        }
    }
}
