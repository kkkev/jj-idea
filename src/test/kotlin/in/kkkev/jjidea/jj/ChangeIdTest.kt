package `in`.kkkev.jjidea.jj

import `in`.kkkev.jjidea.jj.ShortenableId.Companion.calculateShortLength
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for ChangeId - JJ's change identifier with short prefix support.
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
    fun `constructor with full and short length`() {
        val changeId = ChangeId("qpvuntsm", 2)

        changeId.full shouldBe "qpvuntsm"
        changeId.short shouldBe "qp"
        changeId.remainder shouldBe "vuntsm"
        changeId.toString() shouldBe "qp"
    }

    @Test
    fun `constructor with full and short string`() {
        val changeId = ChangeId("qpvuntsm", "qp")

        changeId.full shouldBe "qpvuntsm"
        changeId.short shouldBe "qp"
        changeId.remainder shouldBe "vuntsm"
        changeId.toString() shouldBe "qp"
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
        val changeId = ChangeId("qpvuntsm", 8)

        changeId.short shouldBe "qpvuntsm"
        changeId.remainder shouldBe ""
        changeId.toString() shouldBe "qpvuntsm"
    }

    @Test
    fun `data class equality`() {
        val id1 = ChangeId("qpvuntsm", 2)
        val id2 = ChangeId("qpvuntsm", 2)
        val id3 = ChangeId("qpvuntsm", 3)
        val id4 = ChangeId("different", 2)

        id1 shouldBe id2
        id1 shouldBe id3
        (id1 == id4) shouldBe false
    }

    @Test
    fun `CHARS constant has correct reverse hex mapping`() {
        ChangeId.CHARS shouldBe "zyxwvutsrqponmlk"
    }

    @Test
    fun `HEX constant has correct hex digits`() {
        ChangeId.HEX shouldBe "0123456789abcdef"
    }
}
