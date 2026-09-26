package `in`.kkkev.jjidea.jj.conflict

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [choicesFor]/[replacementFor] (jj-idea-82fo, stage 4/4) - the pure replacement-text logic
 * behind [in.kkkev.jjidea.ui.editor.conflict.AcceptConflictBlockAction]. The action chain itself
 * (write command, undo grouping, read-only gate) is manual-verified, same rationale as
 * `src/test/kotlin/in/kkkev/jjidea/ui/dnd/DropPerformersTest.kt` (needs a live
 * `ApplicationManager`, which a plain unit test doesn't have) - this file is the real test
 * target: every fixture x every applicable choice.
 */
class ConflictBlockReplacementTest {
    @Test
    fun `choicesFor offers BASE only when the block has an explicit base section`() {
        val withBase = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()
        choicesFor(withBase) shouldContainExactly
            listOf(AcceptChoice.SIDE1, AcceptChoice.SIDE2, AcceptChoice.BOTH, AcceptChoice.BASE)

        val snapshot = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.snapshot).single()
        choicesFor(snapshot) shouldContain AcceptChoice.BASE
    }

    @Test
    fun `choicesFor never offers BASE for diff style, even though it has a derived base`() {
        val diff = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.diffDestinationFirst).single()
        diff.base.shouldNotBeNull() // has a derived base...

        choicesFor(diff) shouldNotContain AcceptChoice.BASE
    }

    @Test
    fun `SIDE1 and SIDE2 are that side's lines, newline-terminated`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        replacementFor(block, AcceptChoice.SIDE1) shouldBe "ours content\n"
        replacementFor(block, AcceptChoice.SIDE2) shouldBe "theirs content\n"
    }

    @Test
    fun `BASE is the base section's lines`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        replacementFor(block, AcceptChoice.BASE) shouldBe "base content\n"
    }

    @Test
    fun `BASE on an explicit but empty base section is empty, not a blank line`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitEmptyBase).single()
        block.base?.lines shouldBe emptyList()

        replacementFor(block, AcceptChoice.BASE) shouldBe ""
    }

    @Test
    fun `BASE throws for a block with no base section rather than silently guessing`() {
        val diff = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.diffSide1First).single()
        // diff has a derived base, so use a style that has none at all:
        val noBase = diff.copy(base = null)

        shouldThrow<IllegalArgumentException> { replacementFor(noBase, AcceptChoice.BASE) }
    }

    @Test
    fun `BOTH concatenates side 1 then side 2 with no separator`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()

        replacementFor(block, AcceptChoice.BOTH) shouldBe "ours content\ntheirs content\n"
    }

    @Test
    fun `BOTH with an empty side 1 contributes nothing from it`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()
        val emptySide1 = block.copy(side1 = block.side1.copy(lines = emptyList()))

        replacementFor(emptySide1, AcceptChoice.BOTH) shouldBe "theirs content\n"
    }

    @Test
    fun `no terminating newline on the accepted side is honoured for SIDE2 and BASE, not injected`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitSide2NoTerminatingNewline).single()
        block.side2.noTerminatingNewline shouldBe true

        replacementFor(block, AcceptChoice.SIDE2) shouldBe "theirs content" // no trailing \n
        replacementFor(block, AcceptChoice.SIDE1) shouldBe "ours content\n" // side1 unaffected
    }

    @Test
    fun `BOTH always terminates side 1 regardless of its own flag - side 2 follows immediately`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitSide2NoTerminatingNewline).single()
        // side1 has no explicit "(no terminating newline)" annotation here, but even if it did,
        // BOTH must still terminate it since side2's content follows right after.
        val side1Flagged = block.copy(side1 = block.side1.copy(noTerminatingNewline = true))

        // side2's own flag governs the end:
        replacementFor(side1Flagged, AcceptChoice.BOTH) shouldBe "ours content\ntheirs content"
    }

    @Test
    fun `every fixture with a block accepts every one of its own choicesFor without throwing`() {
        val fixtures = listOf(
            ConflictMarkerFixtures.gitWithBase,
            ConflictMarkerFixtures.gitEmptyBase,
            ConflictMarkerFixtures.snapshot,
            ConflictMarkerFixtures.diffSide1First,
            ConflictMarkerFixtures.diffDestinationFirst,
            ConflictMarkerFixtures.diffFromNamesADistinctSide,
            ConflictMarkerFixtures.cleanRebaseConflictNamingSameCommits,
            ConflictMarkerFixtures.rebaseRoleLabelled,
            ConflictMarkerFixtures.blockAtEofNoTrailingNewline,
            ConflictMarkerFixtures.gitSide2NoTerminatingNewline,
            ConflictMarkerFixtures.literalOpenMarkerInsideContent
        )
        for (text in fixtures) {
            for (block in JjConflictBlockParser.parseAll(text)) {
                for (choice in choicesFor(block)) {
                    replacementFor(block, choice) // must not throw
                }
            }
        }
    }

    @Test
    fun `accepting one block in a multi-block file leaves the other block's own text untouched`() {
        val text = ConflictMarkerFixtures.multiBlock
        val blocks = JjConflictBlockParser.parseAll(text)
        blocks.size shouldBe 2
        val (first, second) = blocks
        val secondTextBefore = text.substring(second.startOffset, second.endOffset)

        val replacement = replacementFor(first, AcceptChoice.SIDE1)
        val newText = text.substring(0, first.startOffset) + replacement + text.substring(first.endOffset)

        val delta = replacement.length - (first.endOffset - first.startOffset)
        val secondTextAfter = newText.substring(second.startOffset + delta, second.endOffset + delta)
        secondTextAfter shouldBe secondTextBefore
    }
}
