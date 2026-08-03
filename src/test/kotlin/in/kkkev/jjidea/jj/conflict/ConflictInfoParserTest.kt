package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ConflictInfoParserTest {
    @Test
    fun `2-sided content conflict`() {
        val info = ConflictInfoParser.parseLine("foo.txt 2-sided conflict")!!

        info.path shouldBe "foo.txt"
        info.sides shouldBe 2
        info.deletions shouldBe 0
        info.isContentOnly shouldBe true
        info.isModifyDelete shouldBe false
    }

    @Test
    fun `2-sided conflict including 1 deletion is modify-delete`() {
        val info = ConflictInfoParser.parseLine("foo.txt 2-sided conflict including 1 deletion")!!

        info.sides shouldBe 2
        info.deletions shouldBe 1
        info.isModifyDelete shouldBe true
        info.isContentOnly shouldBe false
    }

    @Test
    fun `2-sided conflict including 2 deletions`() {
        val info = ConflictInfoParser.parseLine("foo.txt 2-sided conflict including 2 deletions")!!

        info.sides shouldBe 2
        info.deletions shouldBe 2
        info.isModifyDelete shouldBe false
    }

    @Test
    fun `3-sided conflict`() {
        val info = ConflictInfoParser.parseLine("foo.txt 3-sided conflict")!!

        info.sides shouldBe 3
        info.deletions shouldBe 0
    }

    @Test
    fun `3-sided conflict including 1 deletion`() {
        val info = ConflictInfoParser.parseLine("foo.txt 3-sided conflict including 1 deletion")!!

        info.sides shouldBe 3
        info.deletions shouldBe 1
    }

    @Test
    fun `single-space separator - real jj output right-pads the path column`() {
        val info = ConflictInfoParser.parseLine(
            "gateway/lib/gatewaysnapshotvolatileservice.go 2-sided conflict"
        )!!

        info.path shouldBe "gateway/lib/gatewaysnapshotvolatileservice.go"
        info.sides shouldBe 2
    }

    @Test
    fun `multi-space separator`() {
        val info = ConflictInfoParser.parseLine("foo.txt       2-sided conflict including 1 deletion")!!

        info.path shouldBe "foo.txt"
        info.isModifyDelete shouldBe true
    }

    @Test
    fun `blank line yields null`() {
        ConflictInfoParser.parseLine("   ") shouldBe null
    }

    @Test
    fun `unknown trailing text yields sides 0 and raw description`() {
        val info = ConflictInfoParser.parseLine("foo.txt some unrecognized description")!!

        info.path shouldBe "foo.txt"
        info.sides shouldBe 0
        info.deletions shouldBe 0
        info.description shouldBe "some unrecognized description"
    }

    @Test
    fun `parse - multiple lines - keyed by path`() {
        val output =
            """
            a.txt 2-sided conflict
            b.txt 2-sided conflict including 1 deletion
            """.trimIndent()

        val result = ConflictInfoParser.parse(output)

        result.keys shouldBe setOf("a.txt", "b.txt")
        result["b.txt"]?.isModifyDelete shouldBe true
    }
}
