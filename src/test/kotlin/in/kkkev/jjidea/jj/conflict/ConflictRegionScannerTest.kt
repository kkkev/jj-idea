package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Correctness tests for [ConflictRegionScanner] (jj-idea-82fo, stage 2/4). Each test applies one
 * edit via [applyStringEdit] and checks both the returned [Rescan] and that the scanner's
 * resulting [ConflictRegionScanner.blocks] matches a from-scratch [JjConflictBlockParser.parseAll]
 * of the new text - the same equivalence contract stage 1's `ExtractorBlockParserEquivalenceTest`
 * uses for the whole-file extractor. `ConflictRegionScannerEquivalenceTest` generalizes this to
 * randomized edit sequences.
 */
class ConflictRegionScannerTest {
    @Test
    fun `fullScan agrees with JjConflictBlockParser parseAll on every fixture`() {
        for (text in allFixtures()) {
            val scanner = ConflictRegionScanner()
            scanner.fullScan(text) shouldBe JjConflictBlockParser.parseAll(text)
            scanner.degraded shouldBe false
        }
    }

    @Test
    fun `typing plain text far from any block needs no rescan`() {
        val scanner = ConflictRegionScanner()
        val text = scanner.fullScan(ConflictMarkerFixtures.multiBlock).let { ConflictMarkerFixtures.multiBlock }
        val before = scanner.blocks

        val newText = applyStringEdit(scanner, text, changeStart = 0, oldEnd = 0, replacement = "prefix ")

        scanner.blocks shouldBe before.map { it.shifted(7, 0) }
        scanner.blocks shouldBe JjConflictBlockParser.parseAll(newText)
    }

    @Test
    fun `editing plain content inside a block's own side updates that block only, others merely shift`() {
        val scanner = ConflictRegionScanner()
        val text = ConflictMarkerFixtures.multiBlock
        scanner.fullScan(text)
        val secondBlockBefore = scanner.blocks[1]

        // "ours-A" -> "OURS-A" (same length, so offsets of everything after are unchanged)
        val idx = text.indexOf("ours-A")
        val newText = applyStringEdit(scanner, text, changeStart = idx, oldEnd = idx + 6, replacement = "OURS-A")

        scanner.blocks shouldBe JjConflictBlockParser.parseAll(newText)
        scanner.blocks[0].side1.lines shouldBe listOf("OURS-A")
        scanner.blocks[1] shouldBe secondBlockBefore // untouched, zero net offset delta
    }

    @Test
    fun `deleting the closing marker between two blocks merges them into one`() {
        val scanner = ConflictRegionScanner()
        val text = ConflictMarkerFixtures.multiBlock
        scanner.fullScan(text)
        scanner.blocks.size shouldBe 2

        val closer = ">>>>>>> def \"side B\"\nline2\n"
        val closerStart = text.indexOf(closer)
        val newText =
            applyStringEdit(
                scanner,
                text,
                changeStart = closerStart,
                oldEnd = closerStart + closer.length,
                replacement = ""
            )

        scanner.blocks shouldBe JjConflictBlockParser.parseAll(newText)
        scanner.blocks.size shouldBe 1 // the two blocks' content is now one open block up to the second ">>>>>>>"
    }

    @Test
    fun `typing an unterminated open marker into clean text adds no block, matching parseAll`() {
        val scanner = ConflictRegionScanner()
        val text = "plain line one\nplain line two\n"
        scanner.fullScan(text)

        val newText =
            applyStringEdit(
                scanner,
                text,
                changeStart = text.length,
                oldEnd = text.length,
                replacement = "<<<<<<< oops\nstuff\n"
            )

        scanner.blocks shouldBe emptyList()
        scanner.blocks shouldBe JjConflictBlockParser.parseAll(newText)
    }

    @Test
    fun `pasting a whole new conflict block into clean text is detected`() {
        val scanner = ConflictRegionScanner()
        val text = "before\n\nafter\n"
        scanner.fullScan(text)
        scanner.blocks shouldBe emptyList()

        val insertAt = text.indexOf("\n\n") + 1
        val pasted =
            ConflictMarkerFixtures.gitWithBase.substringAfter("context before\n").substringBefore("\ncontext after") +
                "\n"
        val newText = applyStringEdit(scanner, text, changeStart = insertAt, oldEnd = insertAt, replacement = pasted)

        scanner.blocks.size shouldBe 1
        scanner.blocks shouldBe JjConflictBlockParser.parseAll(newText)
    }

    @Test
    fun `deleting a whole block collapses it to nothing`() {
        val scanner = ConflictRegionScanner()
        val text = ConflictMarkerFixtures.gitWithBase
        scanner.fullScan(text)
        val block = scanner.blocks.single()

        val newText =
            applyStringEdit(scanner, text, changeStart = block.startOffset, oldEnd = block.endOffset, replacement = "")

        scanner.blocks shouldBe emptyList()
        scanner.blocks shouldBe JjConflictBlockParser.parseAll(newText)
    }

    @Test
    fun `an unterminated block beyond the search cap degrades to a full scan and matches parseAll`() {
        val scanner = ConflictRegionScanner()
        val hugeTail = buildString { repeat(MAX_OPEN_BLOCK_LINES + 100) { append("filler line\n") } }
        val text = "before\n$hugeTail"
        scanner.fullScan(text)

        val newText = applyStringEdit(scanner, text, changeStart = 0, oldEnd = 0, replacement = "<<<<<<< oops\n")

        scanner.degraded shouldBe true
        scanner.blocks shouldBe emptyList() // still no closer anywhere - parseAll also omits it
        scanner.blocks shouldBe JjConflictBlockParser.parseAll(newText)
    }

    private fun allFixtures(): List<String> = listOf(
        ConflictMarkerFixtures.gitWithBase,
        ConflictMarkerFixtures.gitEmptyBase,
        ConflictMarkerFixtures.snapshot,
        ConflictMarkerFixtures.diffSide1First,
        ConflictMarkerFixtures.diffDestinationFirst,
        ConflictMarkerFixtures.diffFromNamesADistinctSide,
        ConflictMarkerFixtures.cleanRebaseConflictNamingSameCommits,
        ConflictMarkerFixtures.rebaseRoleLabelled,
        ConflictMarkerFixtures.multiBlock,
        ConflictMarkerFixtures.blockAtEofNoTrailingNewline,
        ConflictMarkerFixtures.unterminatedBlock,
        ConflictMarkerFixtures.literalOpenMarkerInsideContent
    )
}

/**
 * Applies one string-level edit to [oldText] via [ConflictRegionScanner.applyEdit] on [scanner],
 * computing `offsetDelta`/`lineDelta` from the edit itself - exactly what a real
 * `DocumentEvent`-driven caller would supply. Returns the resulting new text.
 */
fun applyStringEdit(
    scanner: ConflictRegionScanner,
    oldText: String,
    changeStart: Int,
    oldEnd: Int,
    replacement: String
): String {
    val newText = oldText.substring(0, changeStart) + replacement + oldText.substring(oldEnd)
    val offsetDelta = replacement.length - (oldEnd - changeStart)
    val removedLines = oldText.substring(changeStart, oldEnd).count { it == '\n' }
    val addedLines = replacement.count { it == '\n' }
    scanner.applyEdit(newText, changeStart, changeStart + replacement.length, offsetDelta, addedLines - removedLines)
    return newText
}

fun ConflictBlock.shifted(offsetDelta: Int, lineDelta: Int): ConflictBlock = copy(
    startOffset = startOffset + offsetDelta,
    endOffset = endOffset + offsetDelta,
    startLine = startLine + lineDelta,
    endLine = endLine + lineDelta,
    side1 = side1.shifted(offsetDelta),
    side2 = side2.shifted(offsetDelta),
    base = base?.shifted(offsetDelta)
)

private fun ConflictSide.shifted(offsetDelta: Int): ConflictSide = copy(
    contentStartOffset = contentStartOffset?.plus(offsetDelta),
    contentEndOffset = contentEndOffset?.plus(offsetDelta)
)
