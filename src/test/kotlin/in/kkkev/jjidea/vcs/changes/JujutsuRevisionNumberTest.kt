package `in`.kkkev.jjidea.vcs.changes

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.WorkingCopy
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for JujutsuRevisionNumber
 */
class JujutsuRevisionNumberTest {
    @Test
    fun `revision number returns revision as string`() {
        val revNum = JujutsuRevisionNumber(QUALIFIED_CHANGE_ID)

        revNum.asString() shouldBe "sprottynpurq/7"
    }

    @Test
    fun `working copy revision`() {
        val revNum = JujutsuRevisionNumber(WorkingCopy)

        revNum.asString() shouldBe "@"
    }

    @Test
    fun `parent revision`() {
        val revNum = JujutsuRevisionNumber(WorkingCopy.parent)

        revNum.asString() shouldBe "@-"
    }

    @Test
    fun `equal revisions compare as 0`() {
        val rev1 = JujutsuRevisionNumber(QUALIFIED_CHANGE_ID)
        val rev2 = JujutsuRevisionNumber(QUALIFIED_CHANGE_ID)

        rev1.compareTo(rev2) shouldBe 0
    }

    @Test
    fun `different revisions compare by string`() {
        val rev1 = JujutsuRevisionNumber(ChangeId("vvv", "v"))
        val rev2 = JujutsuRevisionNumber(ChangeId("www", "w"))

        rev1 shouldBeLessThan rev2
        rev2 shouldBeGreaterThan rev1
    }

    @Test
    fun `comparing to non-JujutsuRevisionNumber returns 0`() {
        val rev = JujutsuRevisionNumber(WorkingCopy)

        rev.compareTo(null) shouldBe 0
    }

    companion object {
        val QUALIFIED_CHANGE_ID = ChangeId("sprottynpurq", "sprot", 7)
    }
}
