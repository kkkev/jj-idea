package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [countConflictBlocks] must agree with [JjMarkerConflictExtractor]'s own notion of "how many
 * complete blocks does this file have" - these fixtures mirror
 * [JjMarkerConflictExtractorTest]'s, minus the ones that only exercise label/role parsing (this
 * function only counts blocks, it never looks at header text).
 */
class ConflictBlocksTest {
    @Test
    fun `no conflict markers - zero`() {
        countConflictBlocks("just regular content\nno conflicts here\n") shouldBe 0
    }

    @Test
    fun `empty text - zero`() {
        countConflictBlocks("") shouldBe 0
    }

    @Test
    fun `single snapshot-style block - one`() {
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

        countConflictBlocks(input) shouldBe 1
    }

    @Test
    fun `multiple snapshot-style blocks - counted separately`() {
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

        countConflictBlocks(input) shouldBe 2
    }

    @Test
    fun `git-style multiple blocks - counted separately`() {
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

        countConflictBlocks(input) shouldBe 2
    }

    @Test
    fun `diff format block - one`() {
        val input = """
            |<<<<<<< Conflict 1 of 1
            |+++++++ Contents of side #1
            |ours content
            |%%%%%%% Changes from base to side #2
            |+theirs line
            |\\\        to: side 2
            |>>>>>>> Conflict 1 of 1 ends
        """.trimMargin()

        countConflictBlocks(input) shouldBe 1
    }

    @Test
    fun `unterminated trailing block - not counted`() {
        val input = """
            |before
            |<<<<<<< Conflict 1 of 1
            |+++++++ Contents of side #1
            |ours
            |------- Base
            |base
        """.trimMargin()

        countConflictBlocks(input) shouldBe 0
    }

    @Test
    fun `one resolved block followed by one still-conflicted block - counts only the remaining one`() {
        val input = """
            |resolved content, markers already hand-edited away
            |<<<<<<< Conflict 1 of 1
            |+++++++ Contents of side #1
            |ours
            |------- Base
            |base
            |+++++++ Contents of side #2
            |theirs
            |>>>>>>> Conflict 1 of 1 ends
        """.trimMargin()

        countConflictBlocks(input) shouldBe 1
    }
}
