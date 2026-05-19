package `in`.kkkev.jjidea.vcs.changes

import `in`.kkkev.jjidea.jj.ChangeId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for JujutsuRevisionNumber
 */
class ChangeIdRevisionNumberTest {
    @Test
    fun `revision number returns revision as string`() {
        val revNum = ChangeIdRevisionNumber(QUALIFIED_CHANGE_ID)

        revNum.asString() shouldBe "sprottynpurq/7"
    }

    @Test
    fun `equal revisions compare as 0`() {
        val rev1 = ChangeIdRevisionNumber(QUALIFIED_CHANGE_ID)
        val rev2 = ChangeIdRevisionNumber(QUALIFIED_CHANGE_ID)

        rev1.compareTo(rev2) shouldBe 0
    }

    @Test
    fun `different revisions compare as non-zero`() {
        val rev1 = ChangeIdRevisionNumber(ChangeId("vvv", "v"))
        val rev2 = ChangeIdRevisionNumber(ChangeId("www", "w"))

        rev1.compareTo(rev2) shouldBe -1
        rev2.compareTo(rev1) shouldBe -1
    }

    companion object {
        val QUALIFIED_CHANGE_ID = ChangeId("sprottynpurq", "sprot", 7)
    }
}
