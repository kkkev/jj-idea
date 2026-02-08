package `in`.kkkev.jjidea.jj

import `in`.kkkev.jjidea.jj.Shortenable.Companion.calculateShortLength
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for [ChangeId] - JJ's change identifier with short prefix support.
 */
class ChangeIdTest {
    @Test
    fun `constructor with full ID only`() {
        val changeId = ChangeId("qpvuntsm")

        changeId.full shouldBe "qpvuntsm"
        changeId.short shouldBe "qpvuntsm"
        changeId.remainder shouldBe ""
        changeId.toString() shouldBe "qpvuntsm"
    }

    @Test
    fun `constructor with full and short string`() {
        val changeId = ChangeId("qpvuntsm", "qp")

        changeId.full shouldBe "qpvuntsm"
        changeId.short shouldBe "qp"
        changeId.remainder shouldBe "vuntsm"
        changeId.toString() shouldBe "qpvuntsm"
    }

    @Test
    fun `short string must be prefix of full`() {
        shouldThrow<IllegalArgumentException> {
            ChangeId("qpvuntsm", "xy")
        }
    }

    @Test
    fun `calculateShortLength validates prefix`() {
        val length = calculateShortLength("qpvuntsm", "qp")
        length shouldBe 2
    }

    @Test
    fun `calculateShortLength throws on invalid prefix`() {
        shouldThrow<IllegalArgumentException> {
            calculateShortLength("qpvuntsm", "xy")
        }
    }

    @Test
    fun `short length equal to full length`() {
        val changeId = ChangeId("qpvuntsm")

        changeId.short shouldBe "qpvuntsm"
        changeId.remainder shouldBe ""
        changeId.toString() shouldBe "qpvuntsm"
    }

    @Test
    fun `data class equality`() {
        val id1 = ChangeId("qpvuntsm", "qp")
        val id2 = ChangeId("qpvuntsm", "qp")
        val id3 = ChangeId("qpvuntsm", "qpv")
        val id4 = ChangeId("different", "di")
        val id5 = ChangeId("qpvuntsm", "qp", 0)
        val id6 = ChangeId("qpvuntsm", "qp", 2)

        id1 shouldBe id2
        id1 shouldBe id3
        (id1 == id4) shouldBe false
        (id1 == id5) shouldBe false
        (id1 == id6) shouldBe false
        (id5 == id6) shouldBe false
    }
}
