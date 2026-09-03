package `in`.kkkev.jjidea.settings

import `in`.kkkev.jjidea.jj.InstallMethod
import `in`.kkkev.jjidea.jj.JjAvailabilityStatus
import `in`.kkkev.jjidea.jj.JjExecutableFinder
import `in`.kkkev.jjidea.jj.JjVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Tests for [installHelpIsUpgradeFor] and [detectedInstallMethodFor] — the pure decisions behind
 * Settings → Jujutsu's "Installation Help" group choosing install vs upgrade commands
 * (jj-idea-vwni). Before this, Scenario B (Available but gated on a JjFeature) fell through to
 * "install" commands even though jj was already installed — these tests guard against that
 * regressing.
 */
class InstallHelpTest {
    @Test
    fun `Available and up to date is not an upgrade`() {
        val status = JjAvailabilityStatus.Available(Path.of("jj"), JjVersion(0, 39, 0), InstallMethod.Homebrew)

        installHelpIsUpgradeFor(status) shouldBe false
    }

    @Test
    fun `Available but gated on a feature is an upgrade`() {
        val status = JjAvailabilityStatus.Available(Path.of("jj"), JjVersion(0, 37, 0), InstallMethod.Homebrew)

        installHelpIsUpgradeFor(status) shouldBe true
    }

    @Test
    fun `VersionTooOld is an upgrade`() {
        val status = JjAvailabilityStatus.VersionTooOld(
            Path.of("jj"),
            JjVersion(0, 30, 0),
            JjVersion.MINIMUM,
            InstallMethod.Homebrew,
            emptyList()
        )

        installHelpIsUpgradeFor(status) shouldBe true
    }

    @Test
    fun `Checking, NotFound, and InvalidPath are not an upgrade`() {
        installHelpIsUpgradeFor(JjAvailabilityStatus.Checking) shouldBe false
        installHelpIsUpgradeFor(JjAvailabilityStatus.NotFound(emptyList())) shouldBe false
        installHelpIsUpgradeFor(
            JjAvailabilityStatus.InvalidPath("/bad/path", JjExecutableFinder.InvalidReason.NOT_FOUND, emptyList())
        ) shouldBe false
    }

    @Test
    fun `detectedInstallMethodFor reads installMethod from Available`() {
        val status = JjAvailabilityStatus.Available(Path.of("jj"), JjVersion(0, 37, 0), InstallMethod.Cargo)

        detectedInstallMethodFor(status) shouldBe InstallMethod.Cargo
    }

    @Test
    fun `detectedInstallMethodFor reads installMethod from VersionTooOld`() {
        val status = JjAvailabilityStatus.VersionTooOld(
            Path.of("jj"),
            JjVersion(0, 30, 0),
            JjVersion.MINIMUM,
            InstallMethod.Cargo,
            emptyList()
        )

        detectedInstallMethodFor(status) shouldBe InstallMethod.Cargo
    }

    @Test
    fun `detectedInstallMethodFor is null when the version is unknown`() {
        detectedInstallMethodFor(JjAvailabilityStatus.Checking) shouldBe null
    }
}
