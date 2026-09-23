package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Anti-drift mechanism for the stage-1 refactor (jj-idea-82fo): [JjMarkerConflictExtractor] and
 * the [JjConflictBlockParser]-based scanner infrastructure share exactly one place that knows
 * jj's marker literals/regexes ([JjConflictBlockParser] itself), but this test independently
 * reconstructs [ExtractedConflict]'s three panels directly from [JjConflictBlockParser.parseAll]'s
 * block list - without calling [JjMarkerConflictExtractor] internals - and asserts the result is
 * byte-identical to what the extractor itself produces. If either implementation's interleaving
 * logic ever diverges, this is the test that catches it.
 *
 * Also pins [countConflictBlocks] (the S1 banner's cheap live-count scan) against the parser's
 * own block count, across the same fixture set - the two are intentionally two independent
 * implementations (see [countConflictBlocks]'s own doc), so this is their only shared guarantee.
 */
class ExtractorBlockParserEquivalenceTest {
    private val extractor = JjMarkerConflictExtractor()

    private val fixtures = listOf(
        ConflictMarkerFixtures.gitWithBase,
        ConflictMarkerFixtures.gitEmptyBase,
        ConflictMarkerFixtures.snapshot,
        ConflictMarkerFixtures.diffSide1First,
        ConflictMarkerFixtures.diffDestinationFirst,
        ConflictMarkerFixtures.rebaseRoleLabelled,
        ConflictMarkerFixtures.multiBlock,
        ConflictMarkerFixtures.blockAtEofNoTrailingNewline,
        ConflictMarkerFixtures.literalOpenMarkerInsideContent
    )

    @Test
    fun `reconstructing MergeData from the shared parser's blocks matches the extractor's own output`() {
        for (text in fixtures) {
            val expected = extractor.extract(text.toByteArray(Charsets.UTF_8))
            val reconstructed = reconstructFromParser(text)

            reconstructed?.mergeData?.CURRENT?.toString(Charsets.UTF_8) shouldBe
                expected?.mergeData?.CURRENT?.toString(Charsets.UTF_8)
            reconstructed?.mergeData?.ORIGINAL?.toString(Charsets.UTF_8) shouldBe
                expected?.mergeData?.ORIGINAL?.toString(Charsets.UTF_8)
            reconstructed?.mergeData?.LAST?.toString(Charsets.UTF_8) shouldBe
                expected?.mergeData?.LAST?.toString(Charsets.UTF_8)
            reconstructed?.currentTitle shouldBe expected?.currentTitle
            reconstructed?.lastTitle shouldBe expected?.lastTitle
        }
    }

    @Test
    fun `unterminated block - extractor returns null, matching zero usable blocks from the parser`() {
        val text = ConflictMarkerFixtures.unterminatedBlock
        extractor.extract(text.toByteArray(Charsets.UTF_8)) shouldBe null
        reconstructFromParser(text) shouldBe null
    }

    @Test
    fun `countConflictBlocks agrees with the parser's block count on every fixture`() {
        for (text in fixtures + listOf(ConflictMarkerFixtures.unterminatedBlock)) {
            countConflictBlocks(text) shouldBe JjConflictBlockParser.parseAll(text).size
        }
    }

    /**
     * Independent reimplementation of [JjMarkerConflictExtractor.extract]'s interleaving, built
     * directly from [JjConflictBlockParser.parseAll]'s block list, deliberately not sharing code
     * with the extractor - see class doc.
     */
    private fun reconstructFromParser(text: String): Triple2? {
        val blocks = JjConflictBlockParser.parseAll(text)
        if (blocks.isEmpty()) return null
        if (blocks.size != text.split('\n').count { it.startsWith("<<<<<<<") }) return null

        val opinions = blocks.map { it.side1IsCurrent }.distinct()
        val currentIsSide1 = opinions.singleOrNull() ?: true

        val lines = text.split('\n')
        val current = mutableListOf<String>()
        val original = mutableListOf<String>()
        val last = mutableListOf<String>()
        var lineIndex = 0
        for (block in blocks) {
            while (lineIndex < block.startLine) {
                current += lines[lineIndex]
                original += lines[lineIndex]
                last += lines[lineIndex]
                lineIndex++
            }
            if (currentIsSide1) {
                current += block.side1.lines
                last += block.side2.lines
            } else {
                current += block.side2.lines
                last += block.side1.lines
            }
            original += block.base?.lines ?: emptyList()
            lineIndex = block.endLine + 1
        }
        while (lineIndex < lines.size) {
            current += lines[lineIndex]
            original += lines[lineIndex]
            last += lines[lineIndex]
            lineIndex++
        }

        val firstBlock = blocks.first()
        val (currentTitle, lastTitle) = if (currentIsSide1) {
            firstBlock.side1.label to firstBlock.side2.label
        } else {
            firstBlock.side2.label to firstBlock.side1.label
        }

        return Triple2(
            mergeData = Triple2.SimpleMergeData(
                CURRENT = current.joinToString("\n").toByteArray(Charsets.UTF_8),
                ORIGINAL = original.joinToString("\n").toByteArray(Charsets.UTF_8),
                LAST = last.joinToString("\n").toByteArray(Charsets.UTF_8)
            ),
            currentTitle = currentTitle,
            lastTitle = lastTitle
        )
    }

    /** Minimal local stand-in so this test never touches [ExtractedConflict]/`MergeData` construction directly. */
    private data class Triple2(val mergeData: SimpleMergeData, val currentTitle: String?, val lastTitle: String?) {
        data class SimpleMergeData(val CURRENT: ByteArray, val ORIGINAL: ByteArray, val LAST: ByteArray)
    }
}
