package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Operation-count scale tests for [ConflictRegionScanner] (jj-idea-82fo, stage 2/4, contributing.md
 * Performance & Scale) - pins the incremental cost claims from the bead's design doc, on
 * synthetic input, no wall-clock.
 */
class ConflictRegionScannerScaleTest {
    @Test
    fun `first full scan is O(document length)`() {
        val small = plainLines(1_000)
        val large = plainLines(100_000)

        val scannerSmall = ConflictRegionScanner()
        scannerSmall.fullScan(small)
        val scannerLarge = ConflictRegionScanner()
        scannerLarge.fullScan(large)

        // Both are exactly the text length by construction - the meaningful assertion is the
        // ratio, not the absolute value.
        (scannerLarge.operationCount.toDouble() / scannerSmall.operationCount) shouldBeLessThan 110.0
    }

    @Test
    fun `typing on a plain line in a huge non-conflicted file costs O(edit size), not O(file size)`() {
        val text = plainLines(200_000)
        val scanner = ConflictRegionScanner()
        scanner.fullScan(text)

        val insertAt = text.length / 2
        applyStringEdit(scanner, text, insertAt, insertAt, "x")

        scanner.operationCount shouldBeLessThan 1_000L // a small constant, independent of the 200k-line file
    }

    @Test
    fun `typing far from a conflict block in a huge file costs O(edit size), not O(distance to the block)`() {
        val text = ConflictMarkerFixtures.gitWithBase + "\n" + plainLines(200_000)
        val scanner = ConflictRegionScanner()
        scanner.fullScan(text)
        scanner.blocks.size shouldBe 1

        val insertAt = text.length - 10 // far past the block, near the end of the huge tail
        applyStringEdit(scanner, text, insertAt, insertAt, "x")

        scanner.operationCount shouldBeLessThan 1_000L
    }

    @Test
    fun `editing inside a block's content stays bounded by that block's own extent, not the surrounding file`() {
        val hugeTail = plainLines(200_000)
        val text = ConflictMarkerFixtures.gitWithBase + "\n" + hugeTail
        val scanner = ConflictRegionScanner()
        scanner.fullScan(text)
        val idx = text.indexOf("ours content")

        applyStringEdit(scanner, text, idx, idx + "ours content".length, "OURS CONTENT")

        // Bounded by the block's own size (a handful of lines), regardless of the 200k-line tail after it.
        scanner.operationCount shouldBeLessThan 500L
    }

    @Test
    fun `deleting a closing marker rescans only the two merged blocks' extent, not the whole file`() {
        val prefix = plainLines(50_000)
        val text = prefix + ConflictMarkerFixtures.multiBlock + "\n" + plainLines(50_000)
        val scanner = ConflictRegionScanner()
        scanner.fullScan(text)
        scanner.blocks.size shouldBe 2

        val closer = ">>>>>>> def \"side B\"\nline2\n"
        val closerStart = text.indexOf(closer)
        applyStringEdit(scanner, text, closerStart, closerStart + closer.length, "")

        scanner.blocks.size shouldBe 1
        // Bounded by the two original blocks' combined extent (~20 lines), not the 100k lines of
        // plain filler surrounding them.
        scanner.operationCount shouldBeLessThan 2_000L
    }

    @Test
    fun `pasting a whole file's worth of new content costs one linear pass over the pasted text`() {
        val text = "start\nend\n"
        val scanner = ConflictRegionScanner()
        scanner.fullScan(text)

        val pasted = plainLines(50_000) + ConflictMarkerFixtures.multiBlock
        applyStringEdit(scanner, text, "start\n".length, "start\n".length, pasted)

        scanner.operationCount shouldBeLessThan (2L * pasted.length)
    }

    @Test
    fun `N sequential plain-text edits to a huge file cost O(N times edit size), not O(N times file size)`() {
        val editCount = 200
        var text = plainLines(200_000)
        val scanner = ConflictRegionScanner()
        scanner.fullScan(text)

        var totalOps = 0L
        repeat(editCount) { i ->
            val insertAt = (text.length / (editCount + 1)) * (i + 1)
            text = applyStringEdit(scanner, text, insertAt, insertAt, "y")
            totalOps += scanner.operationCount
        }

        // A from-scratch fullScan per edit would be ~editCount * fileLength - two+ orders of
        // magnitude above a per-edit constant.
        totalOps shouldBeLessThan (500L * editCount)
    }

    @Test
    fun `an unterminated marker beyond the cap trips degraded exactly once and bounds the search cost`() {
        val hugeTail = plainLines(MAX_OPEN_BLOCK_LINES + 1_000)
        val text = "before\n$hugeTail"
        val scanner = ConflictRegionScanner()
        scanner.fullScan(text)

        applyStringEdit(scanner, text, 0, 0, "<<<<<<< oops\n")

        scanner.degraded shouldBe true
        // The cap search itself is bounded, even though the fallback fullScan afterward is O(N)
        // (that's the accepted, one-time cost of degrading, not the thing being bounded here).
        scanner.operationCount shouldBeLessThan (2L * text.length)
    }

    // Fixed-width so ratio assertions between different line counts aren't skewed by digit-count
    // growth in an embedded index.
    private fun plainLines(count: Int): String = buildString {
        repeat(count) { append("plain filler line\n") }
    }
}
