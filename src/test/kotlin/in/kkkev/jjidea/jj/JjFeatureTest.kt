package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path

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

    @Test
    fun `every feature has a resolvable display name`() {
        for (feature in JjFeature.entries) {
            feature.displayName.isBlank() shouldBe false
        }
    }

    @Test
    fun `unsupportedFeatures is empty when the version supports everything`() {
        unsupportedFeatures(JjVersion(0, 39, 0)) shouldBe emptyList()
    }

    @Test
    fun `unsupportedFeatures reports features the version is too old for`() {
        unsupportedFeatures(JjVersion(0, 38, 0)) shouldBe listOf(JjFeature.BOOKMARK_ADVANCE)
    }

    @Test
    fun `unsupportedFeatures is derived from minVersion, not hard-coded`() {
        val version = JjVersion(0, 38, 0)
        unsupportedFeatures(version) shouldBe JjFeature.entries.filter { !version.supports(it) }
    }

    @Test
    fun `unsupportedFeatures reports every feature for a version below all minimums`() {
        unsupportedFeatures(JjVersion(0, 0, 1)) shouldBe JjFeature.entries
    }

    @Test
    fun `unsupportedFeatures for status reports gated features when Available`() {
        val status = JjAvailabilityStatus.Available(Path.of("jj"), JjVersion(0, 38, 0), InstallMethod.Manual)
        unsupportedFeatures(status) shouldBe listOf(JjFeature.BOOKMARK_ADVANCE)
    }

    @Test
    fun `unsupportedFeatures for status is empty when Available and up to date`() {
        val status = JjAvailabilityStatus.Available(Path.of("jj"), JjVersion(0, 39, 0), InstallMethod.Manual)
        unsupportedFeatures(status) shouldBe emptyList()
    }

    @Test
    fun `unsupportedFeatures for status is empty when VersionTooOld`() {
        val status = JjAvailabilityStatus.VersionTooOld(
            Path.of("jj"),
            JjVersion(0, 30, 0),
            JjVersion.MINIMUM,
            InstallMethod.Manual,
            emptyList()
        )
        unsupportedFeatures(status) shouldBe emptyList()
    }

    @Test
    fun `unsupportedFeatures for status is empty when Checking`() {
        unsupportedFeatures(JjAvailabilityStatus.Checking) shouldBe emptyList()
    }

    @Test
    fun `unsupportedFeatures for status is empty when NotFound`() {
        unsupportedFeatures(JjAvailabilityStatus.NotFound(emptyList())) shouldBe emptyList()
    }

    @Test
    fun `unsupportedFeatures for status is empty when InvalidPath`() {
        val status = JjAvailabilityStatus.InvalidPath(
            "/bad/path",
            JjExecutableFinder.InvalidReason.NOT_FOUND,
            emptyList()
        )
        unsupportedFeatures(status) shouldBe emptyList()
    }
}
