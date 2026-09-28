package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.ln

/**
 * Operation-count scale test for [conflictBlockIndexAt] (jj-idea-sr42, contributing.md
 * Performance & Scale) - it backs [in.kkkev.jjidea.ui.editor.conflict.ConflictSideHover]'s
 * per-`mouseMoved`-event hit test, so a regression to a linear scan here would make every mouse
 * move over a large conflicted file cost O(blocks in the file) instead of O(log blocks in the
 * file).
 */
class ConflictHitTestScaleTest {
    @Test
    fun `binary search over N blocks costs O(log N) element accesses, not O(N)`() {
        val n = 100_000
        val blocks = syntheticBlocks(n)
        val counting = CountingList(blocks)

        val bound = (2 * (ln(n.toDouble()) / ln(2.0)).toInt() + 4)

        // A hit inside the first, middle, and last block, plus two misses (the gaps between
        // blocks) - each independently bounded, so an early-exit-only implementation that
        // happens to look fast on one shape can't sneak past this.
        for (offset in listOf(
            blocks.first().startOffset,
            blocks[n / 2].startOffset,
            blocks.last().startOffset,
            blocks[n / 2].endOffset, // the gap between two blocks - a miss
            -1 // before every block - a miss
        )) {
            counting.reset()
            conflictBlockIndexAt(counting, offset)
            counting.accessCount shouldBeLessThan (bound + 1).toLong()
        }
    }

    @Test
    fun `an empty block list costs O(1)`() {
        val counting = CountingList(emptyList())
        conflictBlockIndexAt(counting, 0) shouldBe -1
        counting.accessCount shouldBeLessThan 2L
    }

    /** Non-overlapping, sorted, 3-offset-wide synthetic blocks with a 1-offset gap between them. */
    private fun syntheticBlocks(count: Int): List<ConflictBlock> = (0 until count).map { i ->
        val start = i * 4
        ConflictBlock(
            startOffset = start,
            endOffset = start + 3,
            startLine = i,
            endLine = i,
            style = ConflictMarkerStyle.GIT,
            side1 = ConflictSide(label = null, role = null, lines = emptyList()),
            side2 = ConflictSide(label = null, role = null, lines = emptyList()),
            base = null,
            side1IsCurrent = true
        )
    }

    /** Wraps [delegate], counting [get] calls - the work unit for a binary search over the list. */
    private class CountingList(private val delegate: List<ConflictBlock>) : AbstractList<ConflictBlock>() {
        var accessCount = 0L
            private set

        fun reset() {
            accessCount = 0
        }

        override val size get() = delegate.size

        override fun get(index: Int): ConflictBlock {
            accessCount++
            return delegate[index]
        }
    }
}
