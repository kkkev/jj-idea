package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Covers [sideDisplayLabels]: the shared GitHub #66/#112-driven menu-text choice behind both the
 * S1 editor banner and the bulk "Accept …" actions.
 */
class SideDisplayLabelTest {
    @Test
    fun `single file, distinct labels - both sides use their own title verbatim`() {
        val labels = sideDisplayLabels(
            currentTitles = listOf("abc123 fix the thing"),
            currentAlternateTitles = listOf(null),
            lastTitles = listOf("def456 other change"),
            lastAlternateTitles = listOf(null),
            currentFallback = "Side #1",
            lastFallback = "Side #2"
        )

        labels shouldBe ("abc123 fix the thing" to "def456 other change")
    }

    @Test
    fun `single file with no labels at all - both fall back`() {
        val labels = sideDisplayLabels(listOf(null), listOf(null), listOf(null), listOf(null), "Side #1", "Side #2")

        labels shouldBe ("Side #1" to "Side #2")
    }

    @Test
    fun `single file, colliding titles - current resolves via its own alternate, last via sibling-absorption`() {
        // The reported jj-idea-wk7p shape: a diff-style CURRENT title collides with LAST's own
        // title, but CURRENT's "from:" line named a genuine second commit (its alternate). Once
        // CURRENT moves off the colliding label, LAST's own (never-diff-derived) title survives.
        val changeA = """uvsstouv 0b04d257 "change A" (rebased revision)"""
        val changeB = """ouukwuks b2d02fda "change B" (rebase destination)"""

        val labels = sideDisplayLabels(
            currentTitles = listOf(changeA),
            currentAlternateTitles = listOf(changeB),
            lastTitles = listOf(changeA),
            lastAlternateTitles = listOf(null),
            currentFallback = "Side #1",
            lastFallback = "Side #2"
        )

        labels shouldBe (changeB to changeA)
    }

    @Test
    fun `single file, colliding titles, neither side has an alternate - both fall back together`() {
        val labels = sideDisplayLabels(
            currentTitles = listOf("same"),
            currentAlternateTitles = listOf(null),
            lastTitles = listOf("same"),
            lastAlternateTitles = listOf(null),
            currentFallback = "Side #1",
            lastFallback = "Side #2"
        )

        labels shouldBe ("Side #1" to "Side #2")
    }

    @Test
    fun `several files all sharing the exact same labels - uses them, not a role-only match`() {
        // Several files conflicted by the same rebase/merge naturally get identical labels (same
        // commit, same description, same role) - this is the legitimate multi-file case.
        val changeB = """abc123 "change B" (rebase destination)"""
        val changeA = """def456 "change A" (rebased revision)"""

        val labels = sideDisplayLabels(
            currentTitles = listOf(changeB, changeB, changeB),
            currentAlternateTitles = listOf(null, null, null),
            lastTitles = listOf(changeA, changeA, changeA),
            lastAlternateTitles = listOf(null, null, null),
            currentFallback = "Side #1",
            lastFallback = "Side #2"
        )

        labels shouldBe (changeB to changeA)
    }

    @Test
    fun `several files with different labels on one side - both sides fall back together`() {
        // Two genuinely different commits, even if they'd share the same role word - deliberately
        // not matched, per the "don't coerce unrelated conflicts together" requirement. Falling
        // back on CURRENT alone would still leave LAST showing a specific label, so both fall back.
        val labels = sideDisplayLabels(
            currentTitles = listOf(
                """abc123 "change B" (rebase destination)""",
                """def456 "unrelated" (rebase destination)"""
            ),
            currentAlternateTitles = listOf(null, null),
            lastTitles = listOf("shared last", "shared last"),
            lastAlternateTitles = listOf(null, null),
            currentFallback = "Side #1",
            lastFallback = "Side #2"
        )

        labels shouldBe ("Side #1" to "Side #2")
    }

    @Test
    fun `several files, all colliding but all agreeing on the same alternate - uses the shared alternate pair`() {
        // The multi-select bug a real user reported: two files with the identical
        // diffFromNamesADistinctSide-shaped conflict. Every tier applies the same "every file
        // agrees" rule regardless of selection size, so this resolves exactly like the single-file
        // collision case, not a fallback just because there are two files.
        val changeA = """uvsstouv 0b04d257 "change A" (rebased revision)"""
        val changeB = """ouukwuks b2d02fda "change B" (rebase destination)"""

        val labels = sideDisplayLabels(
            currentTitles = listOf(changeA, changeA),
            currentAlternateTitles = listOf(changeB, changeB),
            lastTitles = listOf(changeA, changeA),
            lastAlternateTitles = listOf(null, null),
            currentFallback = "Side #1",
            lastFallback = "Side #2"
        )

        labels shouldBe (changeB to changeA)
    }

    @Test
    fun `a real reported combination - CURRENT could resolve alone, but LAST can't, so both fall back`() {
        // A real reported combination: file 1 has an internal collision (its CURRENT title equals
        // its own LAST title, "change A", with "change B" available as its alternate). File 2 has
        // no collision at all: its CURRENT genuinely, unambiguously is "change A" and its LAST is
        // "change B". In isolation, CURRENT would resolve confidently to "change A" (both files'
        // raw CURRENT title agrees, and that agreement doesn't collide with LAST's own shared
        // value) - but LAST has no cross-file agreement at all (file 1 says "change A", file 2 says
        // "change B", neither side has a rescuing alternate). Atomicity means CURRENT's success
        // doesn't matter on its own: since LAST can't produce a confident label, BOTH fall back,
        // rather than asserting CURRENT="change A" while LAST shows a bare "Side #2" alongside it.
        val changeA = """uvsstouv 0b04d257 "change A" (rebased revision)"""
        val changeB = """ouukwuks b2d02fda "change B" (rebase destination)"""

        val labels = sideDisplayLabels(
            currentTitles = listOf(changeA, changeA), // file 1's raw (colliding) CURRENT, file 2's real CURRENT
            currentAlternateTitles = listOf(changeB, null),
            lastTitles = listOf(changeA, changeB), // file 1's raw (colliding) LAST, file 2's real LAST
            lastAlternateTitles = listOf(null, null),
            currentFallback = "Side #1",
            lastFallback = "Side #2"
        )

        labels shouldBe ("Side #1" to "Side #2")
    }

    @Test
    fun `two files that disagree on every possible resolution for one side - both sides fall back atomically`() {
        // Neither side can produce a confident, cross-file-agreed label for LAST here (files
        // disagree, no shared alternate) - atomicity means CURRENT falls back too, even though it
        // could otherwise have resolved on its own.
        val resolvableCurrent = "shared current"

        val labels = sideDisplayLabels(
            currentTitles = listOf(resolvableCurrent, resolvableCurrent),
            currentAlternateTitles = listOf(null, null),
            lastTitles = listOf("last one", "last two"),
            lastAlternateTitles = listOf(null, null),
            currentFallback = "Side #1",
            lastFallback = "Side #2"
        )

        labels shouldBe ("Side #1" to "Side #2")
    }
}
