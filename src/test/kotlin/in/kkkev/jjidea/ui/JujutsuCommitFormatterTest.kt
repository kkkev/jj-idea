package `in`.kkkev.jjidea.ui

import `in`.kkkev.jjidea.jj.ChangeId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Tests for formatting commit identifiers in the jj way
 * - Short unique prefix should be distinguishable (bold in UI)
 * - Display version limited to 8 chars or short prefix length
 *
 * Note: Disabled for simple tests as ChangeId requires IntelliJ Platform classes
 */
@Disabled("Requires IntelliJ Platform test fixture")
class JujutsuCommitFormatterTest {

    @Test
    fun `format change ID with short prefix`() {
        val changeId = ChangeId("qpvuntsm", "qp")

        val formatted = JujutsuCommitFormatter.format(changeId)

        formatted.shortPart shouldBe "qp"
        formatted.restPart shouldBe "vuntsm"
        formatted.full shouldBe "qpvuntsm"
    }

    @Test
    fun `format change ID when entire ID is short prefix`() {
        val changeId = ChangeId("ab", "ab")

        val formatted = JujutsuCommitFormatter.format(changeId)

        formatted.shortPart shouldBe "ab"
        formatted.restPart shouldBe ""
        formatted.full shouldBe "ab"
    }

    @Test
    fun `format change ID limits to 8 chars or short prefix length`() {
        val changeId = ChangeId("qpvuntsmlkrxyz", "qp")

        val formatted = JujutsuCommitFormatter.format(changeId)

        formatted.shortPart shouldBe "qp"
        formatted.restPart shouldBe "vuntsm"  // Limited to 8 chars total
        formatted.full shouldBe "qpvuntsm"
    }

    @Test
    fun `format change ID with long short prefix`() {
        val changeId = ChangeId("qpvuntsmlkrxyz", "qpvuntsmlk")  // 10 chars

        val formatted = JujutsuCommitFormatter.format(changeId)

        formatted.shortPart shouldBe "qpvuntsmlk"
        formatted.restPart shouldBe ""  // Matches displayRemainder
        formatted.full shouldBe "qpvuntsmlk"
    }

    // Note: toHtml tests require IntelliJ Platform classes and should be tested in full integration tests
}
