package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JjConflictBlockParserTest {
    @Test
    fun `git format with base - offsets bound exactly the marker block, sides and base extracted`() {
        val text = ConflictMarkerFixtures.gitWithBase
        val blocks = JjConflictBlockParser.parseAll(text)

        blocks shouldBe listOf(blocks.single())
        val block = blocks.single()
        // startOffset/endOffset bound exactly the "<<<<<<<" ... ">>>>>>> ..." marker text.
        text.substring(block.startOffset, block.endOffset) shouldBe
            "<<<<<<< abc \"side A\"\nours content\n||||||| base \"parent\"\nbase content\n=======\ntheirs content\n>>>>>>> def \"side B\"\n"
        text.substring(0, block.startOffset) shouldBe "context before\n"
        text.substring(block.endOffset) shouldBe "context after"

        block.style shouldBe ConflictMarkerStyle.GIT
        block.side1.lines shouldBe listOf("ours content")
        block.side2.lines shouldBe listOf("theirs content")
        block.base.shouldNotBeNull()
        block.base!!.lines shouldBe listOf("base content")
        block.side1IsCurrent shouldBe true
    }

    @Test
    fun `git format with an explicit but empty base section - base is present, not null`() {
        val blocks = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitEmptyBase)

        val block = blocks.single()
        block.style shouldBe ConflictMarkerStyle.GIT
        block.base.shouldNotBeNull()
        block.base!!.lines shouldBe emptyList()
    }

    @Test
    fun `snapshot format - base introduced by dashes is reported as SNAPSHOT style`() {
        val blocks = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.snapshot)

        val block = blocks.single()
        block.style shouldBe ConflictMarkerStyle.SNAPSHOT
        block.side1.lines shouldBe listOf("ours content")
        block.side2.lines shouldBe listOf("theirs content")
        block.base?.lines shouldBe listOf("base content")
        // Snapshot's "Contents of side #N"/"Conflict N of M ends" boilerplate carries no identity.
        block.side1.label.shouldBeNull()
        block.side2.label.shouldBeNull()
    }

    @Test
    fun `diff format, side1 rendered as content - materializes both sides, derives base from the diff`() {
        val blocks = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.diffSide1First)

        val block = blocks.single()
        block.style shouldBe ConflictMarkerStyle.DIFF
        block.side1.lines shouldBe listOf("ours content")
        block.side2.lines shouldBe listOf("theirs content")
        // No removed ("-") line in the %%%%%%% section, so no base content is derivable.
        block.base.shouldBeNull()
        block.side1.label shouldBe "abc123 \"side A\""
        block.side2.label shouldBe "def456 \"side B\""
    }

    @Test
    fun `diff format, destination rendered as the diff section - side order and roles still resolve`() {
        val blocks = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.diffDestinationFirst)

        val block = blocks.single()
        block.style shouldBe ConflictMarkerStyle.DIFF
        // File order: the %%%%%%% (destination) section is side #1, the +++++++ (moved) section side #2.
        block.side1.lines shouldBe listOf("destination content")
        block.side2.lines shouldBe listOf("moved content")
        block.side1.role shouldBe ConflictRole.DESTINATION
        block.side2.role shouldBe ConflictRole.MOVED
        block.base?.lines shouldBe listOf("base content")
        // Destination first, moved second -> reoriented so the moved side lands in CURRENT.
        block.side1IsCurrent shouldBe false
        // The diff's "from:" line named the base (a "parents of ... revision" role) - nothing
        // more specific than the primary label to offer, so no alternate.
        block.side1.alternateLabel.shouldBeNull()
        block.side2.alternateLabel.shouldBeNull()
    }

    @Test
    fun `diff section's own from- line names a genuine side - exposed as an alternate label`() {
        val blocks = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.diffFromNamesADistinctSide)

        val block = blocks.single()
        block.style shouldBe ConflictMarkerStyle.DIFF
        // The diff section's primary label ("to:") collides with the +++++++ section's own label -
        // both name "change A" - but its "from:" line named a genuine other side (the
        // destination), captured as an alternate rather than silently discarded.
        block.side1.label shouldBe """uvsstouv 0b04d257 "change A" (rebased revision)"""
        block.side2.label shouldBe """uvsstouv 0b04d257 "change A" (rebased revision)"""
        block.side1.alternateLabel shouldBe """ouukwuks b2d02fda "change B" (rebase destination)"""
        // The +++++++ section has no "from:" line of its own to draw an alternate from.
        block.side2.alternateLabel.shouldBeNull()
    }

    @Test
    fun `git format with rebase roles - side1IsCurrent reorients to the rebased revision`() {
        val blocks = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.rebaseRoleLabelled)

        val block = blocks.single()
        block.side1.role shouldBe ConflictRole.DESTINATION
        block.side2.role shouldBe ConflictRole.MOVED
        block.side1IsCurrent shouldBe false
    }

    @Test
    fun `multiple blocks - both parsed in document order with correct offsets`() {
        val text = ConflictMarkerFixtures.multiBlock
        val blocks = JjConflictBlockParser.parseAll(text)

        blocks.size shouldBe 2
        val (first, second) = blocks
        first.side1.lines shouldBe listOf("ours-A")
        second.side1.lines shouldBe listOf("ours-B")
        // Blocks are non-overlapping and in ascending offset order.
        (first.endOffset <= second.startOffset) shouldBe true
        text.substring(first.startOffset, first.endOffset).startsWith("<<<<<<<") shouldBe true
        text.substring(second.startOffset, second.endOffset).startsWith("<<<<<<<") shouldBe true
    }

    @Test
    fun `block at end of file with no trailing newline - endOffset lands exactly at text length`() {
        val text = ConflictMarkerFixtures.blockAtEofNoTrailingNewline
        val block = JjConflictBlockParser.parseAll(text).single()

        block.endOffset shouldBe text.length
        text.substring(block.startOffset, block.endOffset) shouldBe text
    }

    @Test
    fun `unterminated block - parseAll returns no blocks at all`() {
        JjConflictBlockParser.parseAll(ConflictMarkerFixtures.unterminatedBlock) shouldBe emptyList()
    }

    @Test
    fun `literal open marker inside a side's content is not treated as a nested block start`() {
        val blocks = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.literalOpenMarkerInsideContent)

        blocks.size shouldBe 1
        blocks.single().side1.lines shouldBe listOf(
            "ours content",
            "<<<<<<< not a real marker, just content",
            "more ours content"
        )
    }

    @Test
    fun `empty document has no blocks`() {
        JjConflictBlockParser.parseAll("") shouldBe emptyList()
    }

    @Test
    fun `document with no markers at all has no blocks`() {
        JjConflictBlockParser.parseAll("just regular content\nno conflicts here\n") shouldBe emptyList()
    }

    @Test
    fun `parseBlockAt returns null for an offset that is not a block's opening marker line`() {
        JjConflictBlockParser.parseBlockAt(ConflictMarkerFixtures.gitWithBase, blockStartOffset = 0).shouldBeNull()
    }

    @Test
    fun `parseBlockAt parses the single block at its known start offset`() {
        val text = ConflictMarkerFixtures.gitWithBase
        val expected = JjConflictBlockParser.parseAll(text).single()

        JjConflictBlockParser.parseBlockAt(text, expected.startOffset) shouldBe expected
    }
}
