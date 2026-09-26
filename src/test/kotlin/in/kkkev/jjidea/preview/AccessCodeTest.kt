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
    @Test
    fun `the shipped legacy code is valid and grants every feature`() {
        AccessCode.grantedFeatures("valid-code-1234") shouldBe PreviewFeature.entries.toSet()
    }

    @Test
    fun `a wrong legacy code grants nothing`() {
        AccessCode.grantedFeatures("wrong-code-0000") shouldBe emptySet()
    }

    @Test
    fun `whitespace and case normalise to the same legacy code`() {
        AccessCode.grantedFeatures("  Valid-Code-1234  ") shouldBe PreviewFeature.entries.toSet()
        AccessCode.grantedFeatures("VALID-CODE-1234") shouldBe PreviewFeature.entries.toSet()
    }

    @Test
    fun `empty and blank input grants nothing`() {
        AccessCode.grantedFeatures("") shouldBe emptySet()
        AccessCode.grantedFeatures("   ") shouldBe emptySet()
    }

    @Test
    fun `a second, rotated legacy hash is also honoured`() {
        AccessCode.grantedFeatures("second-valid-code") shouldBe PreviewFeature.entries.toSet()
    }

    @Test
    fun `comment and blank lines in the legacy resource are not treated as hashes`() {
        AccessCode.grantedFeatures("#a comment line") shouldBe emptySet()
    }

    @Test
    fun `a JJP1-shaped code is invalid without a shipped signing key`() {
        // No preview/code-key.bin on the unit test classpath - this must fail closed, not throw.
        AccessCode.grant("JJP1-AAAA-AAAA-AAAA-AAAA") shouldBe PreviewCode.Grant.Invalid
        AccessCode.grantedFeatures("JJP1-AAAA-AAAA-AAAA-AAAA") shouldBe emptySet()
    }
}
