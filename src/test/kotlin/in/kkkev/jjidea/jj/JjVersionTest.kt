package `in`.kkkev.jjidea.jj

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for [JjVersion] - jj version parsing and comparison.
 */
class JjVersionTest {
    @Test
    fun `parse simple version`() {
        val version = JjVersion.parse("jj 0.37.0")

        version.shouldNotBeNull()
        version.major shouldBe 0
        version.minor shouldBe 37
        version.patch shouldBe 0
        version.toString() shouldBe "0.37.0"
    }

    @Test
    fun `parse version with release candidate suffix`() {
        val version = JjVersion.parse("jj 0.37.0-rc1")

        version.shouldNotBeNull()
        version.major shouldBe 0
        version.minor shouldBe 37
        version.patch shouldBe 0
    }

    @Test
    fun `parse version with dev suffix`() {
        val version = JjVersion.parse("jj 0.38.0-dev")

        version.shouldNotBeNull()
        version.major shouldBe 0
        version.minor shouldBe 38
        version.patch shouldBe 0
    }

    @Test
    fun `parse major version`() {
        val version = JjVersion.parse("jj 1.0.0")

        version.shouldNotBeNull()
        version.major shouldBe 1
        version.minor shouldBe 0
        version.patch shouldBe 0
    }

    @Test
    fun `parse fails without jj prefix`() {
        JjVersion.parse("0.37.0").shouldBeNull()
        JjVersion.parse("git 2.40.0").shouldBeNull()
    }

    @Test
    fun `parse fails with invalid format`() {
        JjVersion.parse("jj").shouldBeNull()
        JjVersion.parse("jj 0.37").shouldBeNull()
        JjVersion.parse("jj invalid").shouldBeNull()
        JjVersion.parse("").shouldBeNull()
    }

    @Test
    fun `version comparison - greater major`() {
        val v1 = JjVersion.parse("jj 1.0.0")!!
        val v2 = JjVersion.parse("jj 0.99.99")!!

        v1 shouldBeGreaterThan v2
    }

    @Test
    fun `version comparison - greater minor`() {
        val v1 = JjVersion.parse("jj 0.38.0")!!
        val v2 = JjVersion.parse("jj 0.37.99")!!

        v1 shouldBeGreaterThan v2
    }

    @Test
    fun `version comparison - greater patch`() {
        val v1 = JjVersion.parse("jj 0.37.1")!!
        val v2 = JjVersion.parse("jj 0.37.0")!!

        v1 shouldBeGreaterThan v2
    }

    @Test
    fun `version comparison - equal`() {
        val v1 = JjVersion.parse("jj 0.37.0")!!
        val v2 = JjVersion.parse("jj 0.37.0")!!

        v1.compareTo(v2) shouldBe 0
    }

    @Test
    fun `meetsMinimum - meets exactly`() {
        val version = JjVersion.parse("jj 0.37.0")!!

        version.meetsMinimum() shouldBe true
    }

    @Test
    fun `meetsMinimum - exceeds`() {
        val version = JjVersion.parse("jj 0.38.0")!!

        version.meetsMinimum() shouldBe true
    }

    @Test
    fun `meetsMinimum - below`() {
        val version = JjVersion.parse("jj 0.36.0")!!

        version.meetsMinimum() shouldBe false
    }

    @Test
    fun `meetsMinimum - major version greater`() {
        val version = JjVersion.parse("jj 1.0.0")!!

        version.meetsMinimum() shouldBe true
    }

    @Test
    fun `MINIMUM constant is 0_37_0`() {
        JjVersion.MINIMUM.major shouldBe 0
        JjVersion.MINIMUM.minor shouldBe 37
        JjVersion.MINIMUM.patch shouldBe 0
    }

    @Test
    fun `parse version with company prefix and long suffix`() {
        val version = JjVersion.parse("jj company-0.39.0-ae610e8da5040c41d19315acc54be334159b2b55-jj-client_20260324_01_RC01-888957854")

        version.shouldNotBeNull()
        version.major shouldBe 0
        version.minor shouldBe 39
        version.patch shouldBe 0
    }

    @Test
    fun `compareTo - less than`() {
        val v1 = JjVersion.parse("jj 0.36.0")!!
        val v2 = JjVersion.parse("jj 0.37.0")!!

        v1 shouldBeLessThan v2
    }
}
