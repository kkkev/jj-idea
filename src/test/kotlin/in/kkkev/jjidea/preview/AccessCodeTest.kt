package `in`.kkkev.jjidea.preview

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [AccessCode] resolves two code families:
 * - legacy hash codes, validated offline against `preview/access-codes.txt` (test resource under
 *   `src/test/resources`, mirroring the plugin resource layout) - always grants every feature.
 * - `JJP1` codes ([PreviewCode]) - the unit test classpath ships no `preview/code-key.bin`
 *   resource (only `PreviewCodeMinter`/release builds inject the real key), so every `JJP1` code
 *   is unconditionally [PreviewCode.Grant.Invalid] here; see [PreviewCodeTest] for the codec
 *   itself and [PreviewEntitlementTest] for the legacy path against the real shipped resource.
 */
class AccessCodeTest {
    private val catalog = TestPreviewFeature.entries

    @Test
    fun `the shipped legacy code is valid and grants every feature`() {
        AccessCode.grantedFeatures("valid-code-1234", catalog = catalog) shouldBe catalog.toSet()
    }

    @Test
    fun `a wrong legacy code grants nothing`() {
        AccessCode.grantedFeatures("wrong-code-0000") shouldBe emptySet()
    }

    @Test
    fun `whitespace and case normalise to the same legacy code`() {
        AccessCode.grantedFeatures("  Valid-Code-1234  ", catalog = catalog) shouldBe catalog.toSet()
        AccessCode.grantedFeatures("VALID-CODE-1234", catalog = catalog) shouldBe catalog.toSet()
    }

    @Test
    fun `empty and blank input grants nothing`() {
        AccessCode.grantedFeatures("") shouldBe emptySet()
        AccessCode.grantedFeatures("   ") shouldBe emptySet()
    }

    @Test
    fun `a second, rotated legacy hash is also honoured`() {
        AccessCode.grantedFeatures("second-valid-code", catalog = catalog) shouldBe catalog.toSet()
    }

    @Test
    fun `comment and blank lines in the legacy resource are not treated as hashes`() {
        AccessCode.grantedFeatures("#a comment line") shouldBe emptySet()
    }

    @Test
    fun `status distinguishes empty, accepted, invalid and cannot-verify`() {
        AccessCode.status("   ") shouldBe PreviewCodeStatus.Empty
        AccessCode.status("valid-code-1234", catalog = catalog) shouldBe
            PreviewCodeStatus.Accepted(catalog.toSet(), expiry = null)
        AccessCode.status("wrong-code-0000") shouldBe PreviewCodeStatus.Invalid
        // A garbage JJP1 code is the build's problem (not a typo) only when there is no signing key
        // to check it against - CI has none, a dev machine with ~/.config/jj-idea/preview-code-key does.
        val expected =
            if (AccessCode.canVerifySignedCodes) PreviewCodeStatus.Invalid else PreviewCodeStatus.CannotVerify
        AccessCode.status("JJP1-AAAA-AAAA-AAAA-AAAA") shouldBe expected
        AccessCode.status("jjp1-aaaa") shouldBe expected
    }

    @Test
    fun `only problem statuses are flagged as problems`() {
        PreviewCodeStatus.Empty.isProblem shouldBe false
        PreviewCodeStatus.Accepted(emptySet(), null).isProblem shouldBe false
        PreviewCodeStatus.Invalid.isProblem shouldBe true
        PreviewCodeStatus.CannotVerify.isProblem shouldBe true
        PreviewCodeStatus.Revoked.isProblem shouldBe true
        PreviewCodeStatus.Expired(java.time.LocalDate.of(2026, 1, 31)).isProblem shouldBe true
    }

    @Test
    fun `a JJP1-shaped code is invalid without a shipped signing key`() {
        // No preview/code-key.bin on the unit test classpath - this must fail closed, not throw.
        AccessCode.grant("JJP1-AAAA-AAAA-AAAA-AAAA") shouldBe PreviewCode.Grant.Invalid
        AccessCode.grantedFeatures("JJP1-AAAA-AAAA-AAAA-AAAA") shouldBe emptySet()
    }
}
