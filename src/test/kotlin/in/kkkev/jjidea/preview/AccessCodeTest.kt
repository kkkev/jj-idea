package `in`.kkkev.jjidea.preview

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [AccessCode] validates offline against the hash(es) shipped in `preview/access-codes.txt`
 * (test resource under `src/test/resources`, mirroring the plugin resource layout).
 */
class AccessCodeTest {
    @Test
    fun `the shipped code is valid`() {
        AccessCode.isValid("valid-code-1234") shouldBe true
    }

    @Test
    fun `a wrong code is invalid`() {
        AccessCode.isValid("wrong-code-0000") shouldBe false
    }

    @Test
    fun `whitespace and case normalise to the same code`() {
        AccessCode.isValid("  Valid-Code-1234  ") shouldBe true
        AccessCode.isValid("VALID-CODE-1234") shouldBe true
    }

    @Test
    fun `empty and blank input is invalid`() {
        AccessCode.isValid("") shouldBe false
        AccessCode.isValid("   ") shouldBe false
    }

    @Test
    fun `a second, rotated hash is also honoured`() {
        AccessCode.isValid("second-valid-code") shouldBe true
    }

    @Test
    fun `comment and blank lines in the resource are not treated as hashes`() {
        AccessCode.isValid("#a comment line") shouldBe false
    }
}
