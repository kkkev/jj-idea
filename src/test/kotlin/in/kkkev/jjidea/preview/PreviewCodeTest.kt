package `in`.kkkev.jjidea.preview

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * [PreviewCode] is the offline codec shared by [AccessCode] (verification) and the (unshipped)
 * minting tool (generation) - see `docs/design/preview-gating-and-dnd-sequencing.md`. Uses a
 * fixed, test-only key throughout; the real key is never present on the unit test classpath (see
 * [AccessCodeTest]'s coverage of that absence).
 */
class PreviewCodeTest {
    private val key = "test-key-not-the-real-one".toByteArray()
    private val otherKey = "a-different-key".toByteArray()
    private val today = LocalDate.of(2026, 6, 15)

    @Test
    fun `round trips a single feature with no expiry`() {
        val code = PreviewCode.encode(setOf(PreviewFeature.PAGED_LOG_LOAD), expiryMonth = 0, serial = 42, key)
        val grant = PreviewCode.verify(code, key, today)
        grant.shouldBeInstanceOf<PreviewCode.Grant.Valid>()
        grant as PreviewCode.Grant.Valid
        grant.features shouldBe setOf(PreviewFeature.PAGED_LOG_LOAD)
        grant.expiry shouldBe null
        grant.serial shouldBe 42
    }

    @Test
    fun `round trips every feature bit individually`() {
        for (feature in PreviewFeature.entries) {
            val code = PreviewCode.encode(setOf(feature), expiryMonth = 0, serial = 1, key)
            (PreviewCode.verify(code, key, today) as PreviewCode.Grant.Valid).features shouldBe setOf(feature)
        }
    }

    @Test
    fun `round trips multiple features together`() {
        val all = PreviewFeature.entries.toSet()
        val code = PreviewCode.encode(all, expiryMonth = 0, serial = 1, key)
        (PreviewCode.verify(code, key, today) as PreviewCode.Grant.Valid).features shouldBe all
    }

    @Test
    fun `the ALL bit grants every feature, not just the ones passed to encode`() {
        // all = true with an empty feature set still grants everything - the wildcard doesn't
        // depend on which individual bits were also requested.
        val code = PreviewCode.encode(emptySet(), expiryMonth = 0, serial = 1, key, all = true)
        (PreviewCode.verify(code, key, today) as PreviewCode.Grant.Valid).features shouldBe
            PreviewFeature.entries.toSet()
    }

    @Test
    fun `expiry - valid through the last day of the granted month`() {
        val month = YearMonth.of(2026, 6)
        val code = PreviewCode.encode(
            setOf(PreviewFeature.DRAG_AND_DROP),
            expiryMonth = PreviewCode.monthsSinceEpoch(month),
            serial = 1,
            key
        )
        PreviewCode.verify(code, key, month.atEndOfMonth()).shouldBeInstanceOf<PreviewCode.Grant.Valid>()
    }

    @Test
    fun `expiry - expired the day after the granted month ends`() {
        val month = YearMonth.of(2026, 6)
        val code = PreviewCode.encode(
            setOf(PreviewFeature.DRAG_AND_DROP),
            expiryMonth = PreviewCode.monthsSinceEpoch(month),
            serial = 1,
            key
        )
        val grant = PreviewCode.verify(code, key, month.atEndOfMonth().plusDays(1))
        grant.shouldBeInstanceOf<PreviewCode.Grant.Expired>()
        (grant as PreviewCode.Grant.Expired).lastValidDate shouldBe month.atEndOfMonth()
    }

    @Test
    fun `expiry month 0 never expires`() {
        val code = PreviewCode.encode(setOf(PreviewFeature.DRAG_AND_DROP), expiryMonth = 0, serial = 1, key)
        PreviewCode.verify(code, key, LocalDate.of(2099, 1, 1)).shouldBeInstanceOf<PreviewCode.Grant.Valid>()
    }

    @Test
    fun `a revoked serial is reported as revoked, not valid`() {
        val code = PreviewCode.encode(setOf(PreviewFeature.PAGED_LOG_LOAD), expiryMonth = 0, serial = 99, key)
        PreviewCode.verify(code, key, today, revokedSerials = setOf(99)) shouldBe PreviewCode.Grant.Revoked
    }

    @Test
    fun `a revoked serial that doesn't match is unaffected`() {
        val code = PreviewCode.encode(setOf(PreviewFeature.PAGED_LOG_LOAD), expiryMonth = 0, serial = 99, key)
        PreviewCode.verify(code, key, today, revokedSerials = setOf(1, 2, 3))
            .shouldBeInstanceOf<PreviewCode.Grant.Valid>()
    }

    @Test
    fun `tampering with any character invalidates the tag`() {
        val code = PreviewCode.encode(setOf(PreviewFeature.PAGED_LOG_LOAD), expiryMonth = 0, serial = 1, key)
        val tampered = code.dropLast(1) + (if (code.last() == 'A') 'B' else 'A')
        PreviewCode.verify(tampered, key, today) shouldBe PreviewCode.Grant.Invalid
    }

    @Test
    fun `verifying with a different key is invalid`() {
        val code = PreviewCode.encode(setOf(PreviewFeature.PAGED_LOG_LOAD), expiryMonth = 0, serial = 1, key)
        PreviewCode.verify(code, otherKey, today) shouldBe PreviewCode.Grant.Invalid
    }

    @Test
    fun `normalisation - lowercase, spaces and dashes are ignored`() {
        val code = PreviewCode.encode(setOf(PreviewFeature.PAGED_LOG_LOAD), expiryMonth = 0, serial = 1, key)
        val messy = "  " + code.lowercase().replace("-", " ") + "  "
        PreviewCode.verify(messy, key, today).shouldBeInstanceOf<PreviewCode.Grant.Valid>()
    }

    @Test
    fun `normalisation - Crockford O I L confusables map to 0 or 1`() {
        // Round trip through a code, then swap every 0/1 for a confusable letter and back again.
        val code = PreviewCode.encode(setOf(PreviewFeature.PAGED_LOG_LOAD), expiryMonth = 0, serial = 1, key)
        val confused = code.map { c ->
            when (c) {
                '0' -> 'O'
                '1' -> 'I'
                else -> c
            }
        }.joinToString("")
        PreviewCode.verify(confused, key, today).shouldBeInstanceOf<PreviewCode.Grant.Valid>()
    }

    @Test
    fun `garbage, wrong length, and empty input are all invalid`() {
        PreviewCode.verify("", key, today) shouldBe PreviewCode.Grant.Invalid
        PreviewCode.verify("JJP1-XXXX", key, today) shouldBe PreviewCode.Grant.Invalid
        PreviewCode.verify("not-a-code-at-all", key, today) shouldBe PreviewCode.Grant.Invalid
        PreviewCode.verify("JJP1-" + "A".repeat(40), key, today) shouldBe PreviewCode.Grant.Invalid
    }

    @Test
    fun `the encoded body is exactly 16 characters after the prefix`() {
        val code = PreviewCode.encode(setOf(PreviewFeature.PAGED_LOG_LOAD), expiryMonth = 0, serial = 1, key)
        code.startsWith("JJP1-") shouldBe true
        code.removePrefix("JJP1-").replace("-", "").length shouldBe 16
    }
}
