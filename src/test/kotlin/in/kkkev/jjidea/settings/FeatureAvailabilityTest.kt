package `in`.kkkev.jjidea.settings

import `in`.kkkev.jjidea.jj.InstallMethod
import `in`.kkkev.jjidea.jj.JjAvailabilityStatus
import `in`.kkkev.jjidea.jj.JjExecutableFinder
import `in`.kkkev.jjidea.jj.JjFeature
import `in`.kkkev.jjidea.jj.JjVersion
import `in`.kkkev.jjidea.jj.unsupportedFeatures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Tests for [featureAvailabilityFor] — the pure display-state decision behind Settings →
 * Jujutsu's "Feature Availability" group (jj-idea-vcqn). The Swing side (panel construction,
 * live status updates) is covered by [JujutsuConfigurablePanelTest] and manual testing
 * (docs/manual-tests.md, MT-SETTINGS), same split as [in.kkkev.jjidea.ui.services.FeatureUpgradeNudge].
 */
class FeatureAvailabilityTest {
    private val outOfDate = JjAvailabilityStatus.Available(Path.of("jj"), JjVersion(0, 38, 0), InstallMethod.Manual)
    private val upToDate = JjAvailabilityStatus.Available(Path.of("jj"), JjVersion(0, 39, 0), InstallMethod.Manual)

    @Test
    fun `Available below a feature's minimum is Gated with that feature`() {
        featureAvailabilityFor(outOfDate) shouldBe
            FeatureAvailability.Gated(outOfDate.version, unsupportedFeatures(outOfDate.version))
    }

    @Test
    fun `Gated is derived from unsupportedFeatures, not hard-coded`() {
        val version = JjVersion(0, 0, 1)
        val status = JjAvailabilityStatus.Available(Path.of("jj"), version, InstallMethod.Manual)

        featureAvailabilityFor(status) shouldBe FeatureAvailability.Gated(version, JjFeature.entries)
    }

    @Test
    fun `Available and up to date is AllSupported`() {
        featureAvailabilityFor(upToDate) shouldBe FeatureAvailability.AllSupported(upToDate.version)
    }

    @Test
    fun `VersionTooOld is BelowMinimum, not AllSupported or Gated`() {
        // Regression guard: Scenario A (below JjVersion.MINIMUM) already has its own balloon and
        // JjNotInstalledPanel — this group must defer to those, never double-report feature
        // gating (or, worse, claim everything is supported) on top of them.
        val status = JjAvailabilityStatus.VersionTooOld(
            Path.of("jj"),
            JjVersion(0, 30, 0),
            JjVersion.MINIMUM,
            InstallMethod.Manual,
            emptyList()
        )

        featureAvailabilityFor(status) shouldBe FeatureAvailability.BelowMinimum(status.version)
    }

    @Test
    fun `Checking is Unknown`() {
        featureAvailabilityFor(JjAvailabilityStatus.Checking) shouldBe FeatureAvailability.Unknown
    }

    @Test
    fun `NotFound is Unknown`() {
        featureAvailabilityFor(JjAvailabilityStatus.NotFound(emptyList())) shouldBe FeatureAvailability.Unknown
    }

    @Test
    fun `InvalidPath is Unknown`() {
        val status = JjAvailabilityStatus.InvalidPath(
            "/bad/path",
            JjExecutableFinder.InvalidReason.NOT_FOUND,
            emptyList()
        )

        featureAvailabilityFor(status) shouldBe FeatureAvailability.Unknown
    }
}
