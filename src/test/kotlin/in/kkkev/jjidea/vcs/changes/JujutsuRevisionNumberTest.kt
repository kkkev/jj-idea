package `in`.kkkev.jjidea.vcs.changes

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.WorkingCopy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for JujutsuRevisionNumber
 */
class JujutsuRevisionNumberTest {
    @Test
    fun `revision number returns revision as string`() {
        val revNum = JujutsuRevisionNumber(CHANGE_ID)

        revNum.asString() shouldBe "7a8b6"
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
        val rev1 = JujutsuRevisionNumber(CHANGE_ID)
        val rev2 = JujutsuRevisionNumber(CHANGE_ID)

        rev1.compareTo(rev2) shouldBe 0
    }

    @Test
    fun `different revisions compare by string`() {
        val rev1 = JujutsuRevisionNumber(ChangeId("vvv"))
        val rev2 = JujutsuRevisionNumber(ChangeId("www"))

        (rev1.compareTo(rev2) < 0) shouldBe true
        (rev2.compareTo(rev1) > 0) shouldBe true
    }

    @Test
    fun `comparing to non-JujutsuRevisionNumber returns 0`() {
        val rev = JujutsuRevisionNumber(WorkingCopy)

        rev.compareTo(null) shouldBe 0
    }

    companion object {
        val CHANGE_ID = ChangeId("sprot")
    }
}
