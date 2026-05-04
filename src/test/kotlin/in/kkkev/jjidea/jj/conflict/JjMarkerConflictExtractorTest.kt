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
        result!!.CURRENT.toString(Charsets.UTF_8) shouldBe "context before\nours content\ncontext after"
        result.ORIGINAL.toString(Charsets.UTF_8) shouldBe "context before\nbase content\ncontext after"
        result.LAST.toString(Charsets.UTF_8) shouldBe "context before\ntheirs content\ncontext after"
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
        result!!.CURRENT.toString(Charsets.UTF_8) shouldBe "line1\nours-A\nline2\nours-B\nline3"
        result.ORIGINAL.toString(Charsets.UTF_8) shouldBe "line1\nbase-A\nline2\nbase-B\nline3"
        result.LAST.toString(Charsets.UTF_8) shouldBe "line1\ntheirs-A\nline2\ntheirs-B\nline3"
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
}
