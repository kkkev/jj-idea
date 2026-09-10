package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Regression net for jj-idea-sc8m's refactor of the graph column's text indent: both
 * [graphTextStartX] (used by `JujutsuLogTable.clickTargetAt`) and
 * [JujutsuGraphAndDescriptionRenderer]'s own private `textStartX()` (used to lay out the actual
 * `textPanel`) now delegate to the same [GraphEdgeIndex.rightmostLane] - this pins them against
 * each other via the renderer's own [JujutsuGraphAndDescriptionRenderer.edgeIndex] (the exact
 * value `textStartX()` reads), so the two can never silently diverge the way the pre-sc8m
 * hand-rolled duplicates could have. Deliberately avoids driving this through a real
 * [javax.swing.JTable]'s `prepareRenderer`/`getTableCellRendererComponent` - those call
 * `Component.getMousePosition()`, which throws `HeadlessException` in this test environment (see
 * [JujutsuLogTableIssueLinkTest]'s doc for the same restriction).
 */
class GraphTextStartXEquivalenceTest {
    private val repo = mockk<JujutsuRepository>()

    private fun entry(id: String, parentIds: List<String> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "commit $id",
        parentIds = parentIds.map { ChangeId(it, it, null) }
    )

    private fun modelWith(entries: List<LogEntry>): JujutsuLogTableModel = JujutsuLogTableModel().apply {
        setEntries(entries)
    }

    /** The x-offset [JujutsuGraphAndDescriptionRenderer]'s own private `textStartX()` would
     * compute for [row] - read via its [JujutsuGraphAndDescriptionRenderer.edgeIndex] instead of
     * driving the private method through a real cell render (see class doc). */
    private fun rendererTextStartX(graphNodes: Map<ChangeKey, GraphNode>, model: JujutsuLogTableModel, row: Int): Int {
        val renderer = JujutsuGraphAndDescriptionRenderer(graphNodes)
        val rightmostLane = renderer.edgeIndex(model).rightmostLane(row)
        return JujutsuGraphAndDescriptionRenderer.HORIZONTAL_PADDING.get() +
            (rightmostLane + 1) * JujutsuGraphAndDescriptionRenderer.LANE_WIDTH.get()
    }

    private fun assertConsistent(entries: List<LogEntry>) {
        val model = modelWith(entries)
        val graphNodes = CommitGraphBuilder().buildGraph(entries)
        entries.indices.forEach { row ->
            graphTextStartX(row, model, graphNodes) shouldBe rendererTextStartX(graphNodes, model, row)
        }
    }

    @Test
    fun `linear chain`() {
        assertConsistent(listOf(entry("a", listOf("b")), entry("b", listOf("c")), entry("c")))
    }

    @Test
    fun `wide DAG with a long passthrough`() {
        // a -> d (skips b, c: opens a passthrough lane spanning their rows).
        assertConsistent(listOf(entry("a", listOf("d")), entry("b", listOf("c")), entry("c"), entry("d")))
    }

    @Test
    fun `merge with a stub for an unresolved parent needs its own lane`() {
        // m merges a loaded parent "p" with an unresolved one - the mixed-merge stub
        // (jj-idea-1pgy) reserves its own lane, which both indent computations must agree on.
        assertConsistent(listOf(entry("m", listOf("p", "missing")), entry("p")))
    }
}
