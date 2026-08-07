package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for [JjFeature.supports] — pure version comparison, no project/settings involved.
 * [disabledReasonIn]/[isSupportedIn] need a live [JjAvailabilityChecker] and are covered by the
 * advance-action availability tests instead.
 */
class JjFeatureTest {
    @Test
    fun `below minimum does not support the feature`() {
        JjVersion(0, 38, 0).supports(JjFeature.BOOKMARK_ADVANCE) shouldBe false
    }

    @Test
    fun `exactly the minimum supports the feature`() {
        JjVersion(0, 39, 0).supports(JjFeature.BOOKMARK_ADVANCE) shouldBe true
    }

    @Test
    fun `above minimum supports the feature`() {
        JjVersion(0, 43, 0).supports(JjFeature.BOOKMARK_ADVANCE) shouldBe true
    }

    @Test
    fun `newer major version supports the feature`() {
        JjVersion(1, 0, 0).supports(JjFeature.BOOKMARK_ADVANCE) shouldBe true
    }
}
