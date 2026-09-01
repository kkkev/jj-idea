package `in`.kkkev.jjidea.ui.services

import `in`.kkkev.jjidea.jj.InstallMethod
import `in`.kkkev.jjidea.jj.JjAvailabilityStatus
import `in`.kkkev.jjidea.jj.JjExecutableFinder
import `in`.kkkev.jjidea.jj.JjFeature
import `in`.kkkev.jjidea.jj.JjVersion
import `in`.kkkev.jjidea.jj.unsupportedFeatures
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettingsState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Tests for [featureNudgeActionFor] — the pure show/skip decision behind [FeatureUpgradeNudge].
 * The notification side-effect itself is covered by manual testing (docs/manual-tests.md,
 * MT-WORKINGCOPY), same as [SponsorAsk]/[WorkingCopySignpost].
 */
class FeatureUpgradeNudgeTest {
    private val outOfDate = JjAvailabilityStatus.Available(Path.of("jj"), JjVersion(0, 38, 0), InstallMethod.Manual)
    private val upToDate = JjAvailabilityStatus.Available(Path.of("jj"), JjVersion(0, 39, 0), InstallMethod.Manual)

    @Test
    fun `Checking never nudges`() {
        featureNudgeActionFor(JjAvailabilityStatus.Checking, shownKey = "") shouldBe null
    }

    @Test
    fun `NotFound never nudges`() {
        featureNudgeActionFor(JjAvailabilityStatus.NotFound(emptyList()), shownKey = "") shouldBe null
    }

    @Test
    fun `InvalidPath never nudges`() {
        val status = JjAvailabilityStatus.InvalidPath(
            "/bad/path",
            JjExecutableFinder.InvalidReason.NOT_FOUND,
            emptyList()
        )
        featureNudgeActionFor(status, shownKey = "") shouldBe null
    }

    @Test
    fun `VersionTooOld never nudges (Scenario A has its own notification)`() {
        val status = JjAvailabilityStatus.VersionTooOld(
            Path.of("jj"),
            JjVersion(0, 30, 0),
            JjVersion.MINIMUM,
            InstallMethod.Manual,
            emptyList()
        )
        featureNudgeActionFor(status, shownKey = "") shouldBe null
    }

    @Test
    fun `Available and up to date never nudges`() {
        featureNudgeActionFor(upToDate, shownKey = "") shouldBe null
    }

    @Test
    fun `Available and out of date nudges with the gated features`() {
        val nudge = featureNudgeActionFor(outOfDate, shownKey = "")

        nudge?.version shouldBe JjVersion(0, 38, 0)
        nudge?.gatedFeatures shouldBe unsupportedFeatures(JjVersion(0, 38, 0))
        nudge?.gatedFeatures shouldBe listOf(JjFeature.BOOKMARK_ADVANCE)
    }

    @Test
    fun `already-shown key for the same version and feature set suppresses the nudge`() {
        val nudge = featureNudgeActionFor(outOfDate, shownKey = "")
        featureNudgeActionFor(outOfDate, shownKey = nudge!!.key) shouldBe null
    }

    @Test
    fun `a shown key for a different version re-fires`() {
        val nudge = featureNudgeActionFor(outOfDate, shownKey = "")
        val differentVersion = JjAvailabilityStatus.Available(Path.of("jj"), JjVersion(0, 37, 0), InstallMethod.Manual)

        featureNudgeActionFor(differentVersion, shownKey = nudge!!.key) shouldNotBe null
    }

    @Test
    fun `a shown key for a smaller gated feature set at the same version re-fires`() {
        // Simulates a plugin update that newly gates another feature at the same jj version:
        // the previously-shown key no longer matches the current gated set.
        val staleKey = "0.38.0|SOME_OTHER_FEATURE"
        featureNudgeActionFor(outOfDate, shownKey = staleKey) shouldNotBe null
    }

    @Test
    fun `featureNudgeShownKey defaults to empty`() {
        JujutsuApplicationSettingsState().featureNudgeShownKey shouldBe ""
    }
}
