package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Output-equivalence property test for [ConflictRegionScanner] (jj-idea-82fo, stage 2/4) - the
 * single highest-value test for an incremental algorithm like this, since a bug that makes
 * [ConflictRegionScanner.applyEdit] diverge from a from-scratch [ConflictRegionScanner.fullScan]
 * (rather than merely being slow) would otherwise go undetected by the targeted correctness
 * cases in `ConflictRegionScannerTest`.
 *
 * A fixed seed keeps this deterministic and reproducible on failure.
 */
class ConflictRegionScannerEquivalenceTest {
    @Test
    fun `N random edits applied incrementally match a from-scratch fullScan after every edit`() {
        val seedStart = ConflictMarkerFixtures.gitWithBase + "\n" +
            ConflictMarkerFixtures.multiBlock + "\n" +
            ConflictMarkerFixtures.diffSide1First + "\n" +
            "some plain trailing text\nwith a few more lines\nand one more\n"

        val random = Random(42)
        val scanner = ConflictRegionScanner()
        var text = seedStart
        scanner.fullScan(text)
        scanner.blocks shouldBe JjConflictBlockParser.parseAll(text)

        val markerSnippets = listOf(
            "<<<<<<< ",
            ">>>>>>> ",
            "=======\n",
            "||||||| ",
            "-------\n",
            "+++++++ ",
            "conflict text\n"
        )

        repeat(300) { iteration ->
            val changeStart = random.nextInt(text.length + 1)
            val removeLen = random.nextInt(minOf(20, text.length - changeStart) + 1)
            val oldEnd = changeStart + removeLen
            val replacement = if (random.nextInt(5) == 0) {
                markerSnippets[random.nextInt(markerSnippets.size)]
            } else {
                "x".repeat(random.nextInt(5))
            }

            text = applyStringEdit(scanner, text, changeStart, oldEnd, replacement)

            val expected = JjConflictBlockParser.parseAll(text)
            withClue(iteration, text) {
                scanner.blocks shouldBe expected
            }
        }
    }

    private inline fun withClue(iteration: Int, text: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("mismatch after edit #$iteration on text:\n$text", e)
        }
    }
}
