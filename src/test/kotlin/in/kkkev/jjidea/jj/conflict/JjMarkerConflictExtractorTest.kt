package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class JjMarkerConflictExtractorTest {
    private val extractor = JjMarkerConflictExtractor()

    @Test
    fun `single conflict block - extracts correct content for each panel`() {
        val input = """
            |context before
            |<<<<<<< Conflict 1 of 1
            |+++++++ Contents of side #1
            |ours content
            |------- Base
            |base content
            |+++++++ Contents of side #2
            |theirs content
            |>>>>>>> Conflict 1 of 1 ends
            |context after
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "context before\nours content\ncontext after"
        result.mergeData.ORIGINAL.toString(Charsets.UTF_8) shouldBe "context before\nbase content\ncontext after"
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "context before\ntheirs content\ncontext after"
        // Snapshot style's "Contents of side #N" boilerplate carries no commit identity.
        result.currentTitle.shouldBeNull()
        result.lastTitle.shouldBeNull()
    }

    @Test
    fun `multiple conflict blocks - all regions substituted in each panel`() {
        val input = """
            |line1
            |<<<<<<< Conflict 1 of 2
            |+++++++ Contents of side #1
            |ours-A
            |------- Base
            |base-A
            |+++++++ Contents of side #2
            |theirs-A
            |>>>>>>> Conflict 1 of 2 ends
            |line2
            |<<<<<<< Conflict 2 of 2
            |+++++++ Contents of side #1
            |ours-B
            |------- Base
            |base-B
            |+++++++ Contents of side #2
            |theirs-B
            |>>>>>>> Conflict 2 of 2 ends
            |line3
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "line1\nours-A\nline2\nours-B\nline3"
        result.mergeData.ORIGINAL.toString(Charsets.UTF_8) shouldBe "line1\nbase-A\nline2\nbase-B\nline3"
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "line1\ntheirs-A\nline2\ntheirs-B\nline3"
    }

    @Test
    fun `no conflict markers returns null`() {
        val input = "just regular content\nno conflicts here\n"
        extractor.extract(input.toByteArray(Charsets.UTF_8)).shouldBeNull()
    }

    @Test
    fun `unclosed conflict block returns null`() {
        val input = """
            |before
            |<<<<<<< Conflict 1 of 1
            |+++++++ Contents of side #1
            |ours
            |------- Base
            |base
        """.trimMargin()
        extractor.extract(input.toByteArray(Charsets.UTF_8)).shouldBeNull()
    }

    @Test
    fun `diff format - side1 then diff section (real jj format)`() {
        // Real jj diff format: +++++++ side1 comes FIRST, then %%%%%%% diff from base to side2
        val input = """
            |context before
            |<<<<<<< conflict 1 of 1
            |+++++++ abc123 "side A"
            |ours content
            |%%%%%%% diff from: parent "base"
            |\\\\\\\ to: def456 "side B"
            |+theirs content
            |>>>>>>> conflict 1 of 1 ends
            |context after
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "context before\nours content\ncontext after"
        result.mergeData.ORIGINAL.toString(Charsets.UTF_8) shouldBe "context before\ncontext after"
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "context before\ntheirs content\ncontext after"
        // Neither header carries a recognised role, so titles come through as jj wrote them.
        result.currentTitle shouldBe "abc123 \"side A\""
        result.lastTitle shouldBe "def456 \"side B\""
    }

    @Test
    fun `git format - single conflict with base - rebase roles reorient Yours to the rebased revision`() {
        // Format used with `ui.conflict-marker-style = "git"` (jj 0.28+). Side A carries the
        // "(rebase destination)" role and side B the "(rebased revision)" role - GitHub #112:
        // "Yours" must land on the rebased revision (side B), not on whichever side jj put first.
        val input = """
            |context before
            |<<<<<<< abc123 "side A" (rebase destination)
            |ours content
            |||||||| parent123 "base" (parents of rebased revision)
            |base content
            |=======
            |theirs content
            |>>>>>>> def456 "side B" (rebased revision)
            |context after
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "context before\ntheirs content\ncontext after"
        result.mergeData.ORIGINAL.toString(Charsets.UTF_8) shouldBe "context before\nbase content\ncontext after"
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "context before\nours content\ncontext after"
        result.currentTitle shouldBe "def456 \"side B\" (rebased revision)"
        result.lastTitle shouldBe "abc123 \"side A\" (rebase destination)"
        result.currentIsJjSide1 shouldBe false
    }

    @Test
    fun `git format - multiple conflict blocks`() {
        val input = """
            |line1
            |<<<<<<< abc "side A"
            |ours-A
            |||||||| base "parent"
            |base-A
            |=======
            |theirs-A
            |>>>>>>> def "side B"
            |line2
            |<<<<<<< abc "side A"
            |ours-B
            |||||||| base "parent"
            |base-B
            |=======
            |theirs-B
            |>>>>>>> def "side B"
            |line3
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "line1\nours-A\nline2\nours-B\nline3"
        result.mergeData.ORIGINAL.toString(Charsets.UTF_8) shouldBe "line1\nbase-A\nline2\nbase-B\nline3"
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "line1\ntheirs-A\nline2\ntheirs-B\nline3"
    }

    @Test
    fun `git format - empty base section`() {
        // Base is empty (content was added on one side, nothing on the other). Side B carries no
        // role text, so there's nothing to reorient against - side A's "(rebase destination)"
        // role alone isn't enough.
        val input = """
            |<<<<<<< abc "side A" (rebase destination)
            |ours content
            |||||||| parent "base"
            |=======
            |theirs content
            |>>>>>>> def "side B"
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "ours content"
        result.mergeData.ORIGINAL.toString(Charsets.UTF_8) shouldBe ""
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "theirs content"
    }

    @Test
    fun `diff format - diff section with context additions and deletions`() {
        val input = """
            |<<<<<<< conflict 1 of 1
            |+++++++ abc123 "side A"
            |ours content
            |%%%%%%% diff from: parent "base"
            |\\\\\\\ to: def456 "side B"
            |unchanged line
            |+added line
            |-removed line
            |>>>>>>> conflict 1 of 1 ends
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "ours content"
        result.mergeData.ORIGINAL.toString(Charsets.UTF_8) shouldBe "unchanged line\nremoved line"
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "unchanged line\nadded line"
    }

    // -------------------------------------------------------------------------
    // GitHub #112: rebase conflicts must name the user's own change "Yours" regardless of
    // which side jj happens to render as full content (+++++++) vs a diff from base (%%%%%%%).
    // -------------------------------------------------------------------------

    @Test
    fun `real jj rebase conflict (reporter's capture, destination first) reorients Yours to the rebased revision`() {
        // Captured verbatim from GitHub #112 (ui.conflict-marker-style = diff, jj 0.44), and
        // reproduced independently against a real jj 0.44 binary for this fix.
        val input = """
            |<<<<<<< conflict 1 of 1
            |+++++++ rtvvumky 17cc31b5 "modified externally" (rebase destination) (no terminating newline)
            |this change done by somebody else
            |%%%%%%% diff from: wyrzptxx b431574d "base" (parents of rebased revision) (no terminating newline)
            |\\\\\\\        to: ulmlywnv c280fd5d "my change" (rebased revision) (no terminating newline)
            |-this is a base content
            |+this is my change
            |>>>>>>> conflict 1 of 1 ends
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "this is my change"
        result.mergeData.ORIGINAL.toString(Charsets.UTF_8) shouldBe "this is a base content"
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "this change done by somebody else"
        result.currentTitle shouldBe "ulmlywnv c280fd5d \"my change\" (rebased revision)"
        result.lastTitle shouldBe "rtvvumky 17cc31b5 \"modified externally\" (rebase destination)"
        result.currentIsJjSide1 shouldBe false
    }

    @Test
    fun `real jj rebase conflict (jj's docs example, destination rendered as a diff first) reorients the same way`() {
        // From jj's bundled CLI docs (conflicts chapter): the SAME kind of 2-sided rebase
        // conflict as the test above, but with the rendering roles swapped - the destination is
        // the %%%%%%% diff section here, and the rebased revision is the +++++++ content
        // section. This is the case that falsifies a fixed "+++++++ is always side #1" rule: the
        // orientation must come out identical to the test above regardless.
        val input = """
            |<<<<<<< conflict 1 of 1
            |%%%%%%% diff from: ovknlmro 7d7c6e6b "B1" (parents of rebased revision)
            |\\\\\\\        to: nuvyytnq 5dda2f09 "A" (rebase destination)
            |-base content
            |+destination content
            |+++++++ puqltutt daa6ffd5 "B2" (rebased revision)
            |moved content
            |>>>>>>> conflict 1 of 1 ends
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "moved content"
        result.mergeData.ORIGINAL.toString(Charsets.UTF_8) shouldBe "base content"
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "destination content"
        result.currentTitle shouldBe "puqltutt daa6ffd5 \"B2\" (rebased revision)"
        result.lastTitle shouldBe "nuvyytnq 5dda2f09 \"A\" (rebase destination)"
        result.currentIsJjSide1 shouldBe false
    }

    @Test
    fun `hypothetical reversed role order - already-correct orientation is left alone`() {
        // Defensive coverage, not an observed jj shape: if a future jj rendering ever put the
        // rebased revision first and the destination second, side #1 would already be "Yours" -
        // no swap should be applied (and none is needed).
        val input = """
            |<<<<<<< conflict 1 of 1
            |+++++++ abc123 "my change" (rebased revision)
            |moved content
            |%%%%%%% diff from: base123 "base" (parents of rebased revision)
            |\\\\\\\        to: def456 "modified externally" (rebase destination)
            |-base content
            |+destination content
            |>>>>>>> conflict 1 of 1 ends
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "moved content"
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "destination content"
        result.currentIsJjSide1 shouldBe true
    }

    @Test
    fun `squash conflict role text does not reorient`() {
        // Squash has a "destination" role but no "moved revision" counterpart - nothing to pair
        // it with, so this must be left exactly as today's side #1 -> CURRENT mapping (the
        // GitHub #112 thread's concern about squash reading backwards if labels were swapped
        // unconditionally).
        val input = """
            |<<<<<<< conflict 1 of 1
            |+++++++ abc123 "destination" (squash destination)
            |ours content
            |%%%%%%% diff from: base123 "base"
            |\\\\\\\        to: def456 "source"
            |+theirs content
            |>>>>>>> conflict 1 of 1 ends
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "ours content"
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "theirs content"
        result.currentIsJjSide1 shouldBe true
    }

    @Test
    fun `merge conflict with no role text is unaffected`() {
        // Merge conflicts (two arbitrary commits combined, neither "destination" nor "moved")
        // carry no role annotation at all - there's nothing to reorient, so side #1 -> CURRENT
        // stays the mapping, exactly as before this fix.
        val input = """
            |<<<<<<< abc "left"
            |left content
            |||||||| base "base"
            |base content
            |=======
            |right content
            |>>>>>>> def "right"
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "left content"
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "right content"
    }

    @Test
    fun `two blocks with disagreeing orientation - file left unreoriented`() {
        val agreeing = """
            |+++++++ abc "destination" (rebase destination)
            |dest-A
            |%%%%%%% diff from: base "base" (parents of rebased revision)
            |\\\\\\\        to: def "moved" (rebased revision)
            |+moved-A
        """.trimMargin()
        val disagreeing = """
            |+++++++ def "moved" (rebased revision)
            |moved-B
            |%%%%%%% diff from: base "base" (parents of rebased revision)
            |\\\\\\\        to: abc "destination" (rebase destination)
            |+dest-B
        """.trimMargin()
        val input = "<<<<<<< conflict 1 of 2\n$agreeing\n>>>>>>> conflict 1 of 2 ends\n" +
            "<<<<<<< conflict 2 of 2\n$disagreeing\n>>>>>>> conflict 2 of 2 ends"

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        // Block 1 wants a swap (destination first, moved second); block 2 is already correctly
        // oriented (moved first, destination second) - they disagree on whether CURRENT should
        // hold side #1 or side #2, so neither block is reoriented.
        result shouldNotBe null
        result!!.mergeData.CURRENT.toString(Charsets.UTF_8) shouldBe "dest-A\nmoved-B"
        result.mergeData.LAST.toString(Charsets.UTF_8) shouldBe "moved-A\ndest-B"
        result.currentIsJjSide1 shouldBe true
    }
}
