package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * [conflictBlockIndexAt]/[ConflictBlock.sideAt] - the hit test
 * [in.kkkev.jjidea.ui.editor.conflict.ConflictSideHover] (jj-idea-sr42) runs on every
 * `mouseMoved` event.
 */
class ConflictHitTestTest {
    @Test
    fun `every offset of a git-style block with a base resolves to the correct side, or null on a marker line`() {
        val text = ConflictMarkerFixtures.gitWithBase
        val blocks = JjConflictBlockParser.parseAll(text)
        blocks.size shouldBe 1
        val block = blocks[0]

        assertBruteForceEquivalence(blocks, text)

        offsetOf(text, "ours content").let { block.sideAt(it) shouldBe AcceptChoice.SIDE1 }
        offsetOf(text, "base content").let { block.sideAt(it) shouldBe AcceptChoice.BASE }
        offsetOf(text, "theirs content").let { block.sideAt(it) shouldBe AcceptChoice.SIDE2 }
        offsetOf(text, "<<<<<<<").let { block.sideAt(it) shouldBe null }
        offsetOf(text, "|||||||").let { block.sideAt(it) shouldBe null }
        offsetOf(text, "=======").let { block.sideAt(it) shouldBe null }
        offsetOf(text, ">>>>>>>").let { block.sideAt(it) shouldBe null }
        offsetOf(text, "context before").let { block.sideAt(it) shouldBe null }
        offsetOf(text, "context after").let { block.sideAt(it) shouldBe null }
    }

    @Test
    fun `an explicit but empty base resolves to null everywhere - nothing to hover`() {
        val text = ConflictMarkerFixtures.gitEmptyBase
        val blocks = JjConflictBlockParser.parseAll(text)
        val block = blocks[0]

        requireNotNull(block.base)
        block.base?.contentStartOffset shouldBe block.base?.contentEndOffset

        val baseLineOffset = offsetOf(text, "|||||||")
        block.sideAt(baseLineOffset) shouldBe null
    }

    @Test
    fun `a diff-style block's derived base has no offsets, so sideAt never resolves BASE for it`() {
        val text = ConflictMarkerFixtures.diffDestinationFirst
        val blocks = JjConflictBlockParser.parseAll(text)
        val block = blocks[0]

        requireNotNull(block.base)
        block.base?.contentStartOffset shouldBe null

        for (offset in 0..text.length) {
            block.sideAt(offset) shouldNotBe AcceptChoice.BASE
        }
    }

    @Test
    fun `conflictBlockIndexAt finds the right block among several, and -1 between or after them`() {
        val text = ConflictMarkerFixtures.multiBlock
        val blocks = JjConflictBlockParser.parseAll(text)
        blocks.size shouldBe 2

        assertBruteForceEquivalence(blocks, text)

        conflictBlockIndexAt(blocks, offsetOf(text, "ours-A")) shouldBe 0
        conflictBlockIndexAt(blocks, offsetOf(text, "ours-B")) shouldBe 1
        conflictBlockIndexAt(blocks, offsetOf(text, "line1")) shouldBe -1
        conflictBlockIndexAt(blocks, offsetOf(text, "line2")) shouldBe -1
        conflictBlockIndexAt(blocks, offsetOf(text, "line3")) shouldBe -1
        conflictBlockIndexAt(blocks, -1) shouldBe -1
        conflictBlockIndexAt(blocks, text.length) shouldBe -1
    }

    @Test
    fun `no blocks in the file - every offset resolves to -1`() {
        val text = "plain\ntext\nonly\n"
        conflictBlockIndexAt(emptyList(), 0) shouldBe -1
        conflictBlockIndexAt(emptyList(), text.length) shouldBe -1
    }

    /** Brute-force linear scan, pinned against [conflictBlockIndexAt]'s binary search at every offset. */
    private fun assertBruteForceEquivalence(blocks: List<ConflictBlock>, text: String) {
        for (offset in 0..text.length) {
            val expected = blocks.indexOfFirst { offset >= it.startOffset && offset < it.endOffset }
            conflictBlockIndexAt(blocks, offset) shouldBe expected
        }
    }

    private fun offsetOf(text: String, needle: String): Int {
        val i = text.indexOf(needle)
        check(i >= 0) { "fixture text doesn't contain \"$needle\"" }
        return i
    }
}
