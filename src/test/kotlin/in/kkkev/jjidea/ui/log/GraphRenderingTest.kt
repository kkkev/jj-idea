package `in`.kkkev.jjidea.ui.log

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * TDD tests for commit graph rendering with a simple canvas test harness.
 *
 * Tests verify properties like:
 * - No broken lines (lines are continuous at cell boundaries)
 * - Lines have correct colors
 * - Lines connect expected points
 */
class GraphRenderingTest {
    companion object {
        private const val LANE_WIDTH = 16
        private const val ROW_HEIGHT = 22
        private const val COMMIT_RADIUS = 4
    }

    /**
     * Simple point in 2D space.
     */
    data class Point(
        val x: Int,
        val y: Int
    ) {
        override fun toString() = "($x, $y)"
    }

    /**
     * A line segment with color.
     */
    data class LineSegment(
        val from: Point,
        val to: Point,
        val color: Color
    ) {
        fun isVertical() = from.x == to.x

        fun isHorizontal() = from.y == to.y

        fun isDiagonal() = !isVertical() && !isHorizontal()

        override fun toString() = "$from -> $to [${colorName(color)}]"

        private fun colorName(c: Color): String =
            when {
                c == Color(0x4285F4) -> "Blue"
                c == Color(0xEA4335) -> "Red"
                c == Color(0xFBBC04) -> "Yellow"
                c == Color(0x34A853) -> "Green"
                c == Color(0xFF6D00) -> "Orange"
                c == Color(0x9C27B0) -> "Purple"
                c == Color(0x00ACC1) -> "Cyan"
                c == Color(0x7CB342) -> "LightGreen"
                else -> "Color(${c.rgb})"
            }
    }

    /**
     * Simple canvas that records line drawing operations.
     */
    class Canvas {
        private val lines = mutableListOf<LineSegment>()

        fun drawLine(
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int,
            color: Color
        ) {
            lines.add(LineSegment(Point(x1, y1), Point(x2, y2), color))
        }

        fun getLines() = lines.toList()

        fun getLinesInRow(row: Int): List<LineSegment> {
            val rowTop = row * ROW_HEIGHT
            val rowBottom = rowTop + ROW_HEIGHT
            return lines.filter { line ->
                // Line is in this row if either endpoint is in row bounds
                (line.from.y in rowTop..rowBottom) || (line.to.y in rowTop..rowBottom)
            }
        }

        fun printLines() {
            lines.forEachIndexed { index, line ->
                println("  $index: $line")
            }
        }

        /**
         * Verify no broken lines - lines of same color should connect at cell boundaries.
         */
        fun assertNoBrokenLines(
            color: Color,
            startRow: Int,
            endRow: Int
        ) {
            val colorLines = lines.filter { it.color == color }
            val rowTop = startRow * ROW_HEIGHT
            val rowBottom = endRow * ROW_HEIGHT + ROW_HEIGHT

            // For each row boundary, verify lines connect
            for (row in startRow until endRow) {
                val boundaryY = (row + 1) * ROW_HEIGHT
                val linesEndingAtBoundary = colorLines.filter { it.to.y == boundaryY }
                val linesStartingAtBoundary = colorLines.filter { it.from.y == boundaryY }

                if (linesEndingAtBoundary.isNotEmpty() || linesStartingAtBoundary.isNotEmpty()) {
                    // Should have both (line continues across boundary)
                    linesEndingAtBoundary.shouldHaveSize(1)
                    linesStartingAtBoundary.shouldHaveSize(1)

                    // And they should connect (same X or form diagonal)
                    val endX = linesEndingAtBoundary.first().to.x
                    val startX = linesStartingAtBoundary.first().from.x
                    // Allow some tolerance for diagonal lines
                    (endX == startX) shouldBe true
                }
            }
        }

        /**
         * Assert a line segment exists (approximately, allowing small differences).
         */
        fun assertLineExists(
            from: Point,
            to: Point,
            color: Color
        ) {
            val exists =
                lines.any { line ->
                    line.color == color &&
                        pointsClose(line.from, from) &&
                        pointsClose(line.to, to)
                }
            if (!exists) {
                println("Expected line not found: $from -> $to [color=$color]")
                println("Available lines:")
                printLines()
            }
            exists shouldBe true
        }

        private fun pointsClose(
            p1: Point,
            p2: Point,
            tolerance: Int = 2
        ): Boolean = Math.abs(p1.x - p2.x) <= tolerance && Math.abs(p1.y - p2.y) <= tolerance
    }

    /**
     * Simulates rendering of graph cells to a canvas.
     * This mimics the logic in JujutsuGraphCellRenderer but outputs to Canvas instead of Graphics2D.
     */
    class GraphRenderer(
        private val graph: Map<String, GraphBuilder.Node>,
        private val entries: List<GraphBuilder.Entry>
    ) {
        fun render(canvas: Canvas) {
            // Render each row
            for ((rowIndex, entry) in entries.withIndex()) {
                val node = graph[entry.id] ?: continue
                renderRow(canvas, rowIndex, entry, node)
            }
        }

        private fun renderRow(
            canvas: Canvas,
            row: Int,
            entry: GraphBuilder.Entry,
            node: GraphBuilder.Node
        ) {
            val rowTop = row * ROW_HEIGHT
            val rowBottom = rowTop + ROW_HEIGHT
            val rowMiddle = rowTop + ROW_HEIGHT / 2

            // 1. Draw pass-through lines (vertical lines for child lanes passing through this row)
            // Look at all previous commits (children) that have parents in rows after this one
            for (prevRowIndex in 0 until row) {
                val prevEntry = entries[prevRowIndex]
                val prevNode = graph[prevEntry.id] ?: continue

                // Check if this child has a parent that comes after this row
                for (parentId in prevEntry.parentIds) {
                    val parentRowIndex = entries.indexOfFirst { it.id == parentId }
                    if (parentRowIndex > row) {
                        // This child's line passes through this row in the child's lane
                        val childLane = prevNode.lane
                        val childX = LANE_WIDTH / 2 + childLane * LANE_WIDTH
                        canvas.drawLine(childX, rowTop, childX, rowBottom, prevNode.color)
                        break // Only draw once per child
                    }
                }
            }

            // 2. Calculate commit position
            val commitX = LANE_WIDTH / 2 + node.lane * LANE_WIDTH
            val commitY = rowMiddle

            // 3. Draw incoming lines from children (from their lanes at top of this row to this commit)
            // Look through ALL previous rows to find children that have this commit as a parent
            for (prevRowIndex in 0 until row) {
                val prevEntry = entries[prevRowIndex]
                val prevNode = graph[prevEntry.id] ?: continue

                // Check if this previous commit has current commit as a parent
                val parentIndex = prevEntry.parentIds.indexOf(entry.id)
                if (parentIndex >= 0) {
                    // Draw from CHILD's lane at TOP of THIS row to THIS commit's center
                    // The line travels in the child's lane through pass-through rows,
                    // then diagonals (or stays vertical) to reach this commit
                    val childLane = prevNode.lane
                    val childX = LANE_WIDTH / 2 + childLane * LANE_WIDTH
                    canvas.drawLine(childX, rowTop, commitX, commitY, prevNode.color)
                }
            }

            // 4. Draw outgoing lines to parents
            // Always draw vertical in THIS commit's lane - the line continues through pass-through
            // until it reaches the parent, where it diagonals to the parent's position
            if (node.parentLanes.isNotEmpty()) {
                // Draw vertical line from commit center to bottom of cell (in THIS commit's lane)
                canvas.drawLine(commitX, commitY, commitX, rowBottom, node.color)
            }
        }
    }

    @Nested
    inner class `Three Children in Different Lanes` {
        @Test
        fun `parent with three children - same lane child should have vertical line`() {
            // User's test case:
            // dd (lane 1) --> aa (lane 1) - vertical line is broken immediately above aa (BUG)
            // cc (lane 2) --> aa (lane 1) - should start vertical, then diagonal
            // bb (lane 3) --> aa (lane 1) - rendered perfectly
            val entries =
                listOf(
                    entry("dd", listOf("aa")), // Lane 1 -> lane 1 (should be vertical)
                    entry("cc", listOf("aa")), // Lane 2 -> lane 1 (diagonal)
                    entry("bb", listOf("aa")), // Lane 3 -> lane 1 (diagonal)
                    entry("aa") // Lane 1
                )

            val builder = GraphBuilder()
            val graph = builder.buildGraph(entries)

            // Verify lane assignments
            graph["dd"]!!.lane shouldBe 0 // First child gets lane 0
            graph["cc"]!!.lane shouldBe 1 // Second child gets lane 1
            graph["bb"]!!.lane shouldBe 2 // Third child gets lane 2
            graph["aa"]!!.lane shouldBe 0 // Parent continues in first child's lane

            // Render to canvas
            val canvas = Canvas()
            val renderer = GraphRenderer(graph, entries)
            renderer.render(canvas)

            println("Lines drawn:")
            canvas.printLines()

            // Test 1: dd -> aa (same lane, should be vertical)
            // Row 0: dd at (8, 11) in lane 0
            // Row 3: aa at (8, 77) in lane 0
            // Should have continuous vertical line from dd to aa

            val ddX = LANE_WIDTH / 2 + 0 * LANE_WIDTH // Lane 0 = 8
            val ddY = 0 * ROW_HEIGHT + ROW_HEIGHT / 2 // Row 0 middle = 11

            val aaX = LANE_WIDTH / 2 + 0 * LANE_WIDTH // Lane 0 = 8
            val aaY = 3 * ROW_HEIGHT + ROW_HEIGHT / 2 // Row 3 middle = 77

            // From dd to bottom of row 0
            canvas.assertLineExists(
                Point(ddX, ddY),
                Point(ddX, ROW_HEIGHT), // Should be vertical to bottom of cell
                graph["dd"]!!.color
            )

            // TODO: Add more assertions for continuity through rows 1 and 2
        }

        @Test
        fun `parent with three children - diagonal line should start vertical then diagonal`() {
            // cc (lane 2) --> aa (lane 1)
            // Below cc, line should:
            // - Start vertical from cc
            // - Continue vertical through row with bb
            // - Then diagonal to aa
            val entries =
                listOf(
                    entry("dd", listOf("aa")),
                    entry("cc", listOf("aa")),
                    entry("bb", listOf("aa")),
                    entry("aa")
                )

            val builder = GraphBuilder()
            val graph = builder.buildGraph(entries)

            val canvas = Canvas()
            val renderer = GraphRenderer(graph, entries)
            renderer.render(canvas)

            // cc is in lane 1, aa is in lane 0
            val ccX = LANE_WIDTH / 2 + 1 * LANE_WIDTH // Lane 1
            val ccY = 1 * ROW_HEIGHT + ROW_HEIGHT / 2

            // From cc, should go vertical first (in cc's lane)
            val linesFromCc =
                canvas.getLines().filter {
                    it.from.x == ccX && it.from.y == ccY && it.color == graph["cc"]!!.color
                }

            linesFromCc.shouldHaveSize(1)

            // TODO: Verify line goes vertical through row 2, then diagonal to aa
        }

        @Test
        fun `no broken lines - all lines should be continuous`() {
            val entries =
                listOf(
                    entry("dd", listOf("aa")),
                    entry("cc", listOf("aa")),
                    entry("bb", listOf("aa")),
                    entry("aa")
                )

            val builder = GraphBuilder()
            val graph = builder.buildGraph(entries)

            val canvas = Canvas()
            val renderer = GraphRenderer(graph, entries)
            renderer.render(canvas)

            println("\n=== Lines drawn for 'no broken lines' test ===")
            canvas.printLines()
            println("dd lane: ${graph["dd"]!!.lane}, color: ${graph["dd"]!!.color}")
            println("cc lane: ${graph["cc"]!!.lane}, color: ${graph["cc"]!!.color}")
            println("bb lane: ${graph["bb"]!!.lane}, color: ${graph["bb"]!!.color}")
            println("aa lane: ${graph["aa"]!!.lane}, color: ${graph["aa"]!!.color}")

            // Verify no breaks for each child-parent connection
            canvas.assertNoBrokenLines(graph["dd"]!!.color, startRow = 0, endRow = 3)
            canvas.assertNoBrokenLines(graph["cc"]!!.color, startRow = 1, endRow = 3)
            canvas.assertNoBrokenLines(graph["bb"]!!.color, startRow = 2, endRow = 3)
        }
    }
}
