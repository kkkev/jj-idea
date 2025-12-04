package `in`.kkkev.jjidea.vcs.changes

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for JujutsuRevisionNumber
 */
class JujutsuRevisionNumberTest {

    @Test
    fun `revision number returns revision as string`() {
        val revNum = JujutsuRevisionNumber("abc123")

        revNum.asString() shouldBe "abc123"
    }

    @Test
    fun `working copy revision`() {
        val revNum = JujutsuRevisionNumber("@")

        revNum.asString() shouldBe "@"
    }

    @Test
    fun `parent revision`() {
        val revNum = JujutsuRevisionNumber("@-")

        revNum.asString() shouldBe "@-"
    }

    @Test
    fun `equal revisions compare as 0`() {
        val rev1 = JujutsuRevisionNumber("abc123")
        val rev2 = JujutsuRevisionNumber("abc123")

        rev1.compareTo(rev2) shouldBe 0
    }

    @Test
    fun `different revisions compare by string`() {
        val rev1 = JujutsuRevisionNumber("aaa")
        val rev2 = JujutsuRevisionNumber("bbb")

        (rev1.compareTo(rev2) < 0) shouldBe true
        (rev2.compareTo(rev1) > 0) shouldBe true
    }

    @Test
    fun `comparing to non-JujutsuRevisionNumber returns 0`() {
        val rev = JujutsuRevisionNumber("@")

        rev.compareTo(null) shouldBe 0
    }
}