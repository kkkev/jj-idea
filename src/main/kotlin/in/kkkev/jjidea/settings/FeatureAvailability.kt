package `in`.kkkev.jjidea.settings

import `in`.kkkev.jjidea.jj.InstallMethod
import `in`.kkkev.jjidea.jj.JjAvailabilityStatus
import `in`.kkkev.jjidea.jj.JjFeature
import `in`.kkkev.jjidea.jj.JjVersion
import `in`.kkkev.jjidea.jj.unsupportedFeatures

/**
 * Display model behind Settings → Jujutsu's "Feature Availability" group (jj-idea-vcqn, surface
 * 1 of jj-idea-xuah's Scenario B — "jj meets the minimum but is too old for a [JjFeature]").
 * Pure and Swing-free so it's unit-testable without building the settings panel.
 */
internal sealed interface FeatureAvailability {
    /** [version] is too old for one or more features — the group should auto-expand. */
    data class Gated(val version: JjVersion, val features: List<JjFeature>) : FeatureAvailability

    /** [version] supports every known [JjFeature]. */
    data class AllSupported(val version: JjVersion) : FeatureAvailability

    /**
     * Scenario A: [version] is below [JjVersion.MINIMUM]. That case already has its own
     * proactive balloon and tool-window panel (see jj-idea-xuah) — this group must defer to
     * those, not double-report feature gating on top of them.
     */
    data class BelowMinimum(val version: JjVersion) : FeatureAvailability

    /** jj's version isn't known yet ([JjAvailabilityStatus.Checking]/`NotFound`/`InvalidPath`). */
    data object Unknown : FeatureAvailability
}

/**
 * Derives the settings-page display state from [status]. Reuses [unsupportedFeatures], the
 * shared core (jj-idea-gg1r) behind both this and the one-time upgrade nudge
 * ([in.kkkev.jjidea.ui.services.FeatureUpgradeNudge]) — never re-derive gating from
 * [JjFeature.minVersion] directly.
 */
internal fun featureAvailabilityFor(status: JjAvailabilityStatus): FeatureAvailability =
    when (status) {
        is JjAvailabilityStatus.Available -> {
            val gated = unsupportedFeatures(status)
            if (gated.isEmpty()) {
                FeatureAvailability.AllSupported(status.version)
            } else {
                FeatureAvailability.Gated(status.version, gated)
            }
        }

        is JjAvailabilityStatus.VersionTooOld -> FeatureAvailability.BelowMinimum(status.version)
        is JjAvailabilityStatus.Checking,
        is JjAvailabilityStatus.NotFound,
        is JjAvailabilityStatus.InvalidPath -> FeatureAvailability.Unknown
    }

/**
 * Whether Settings → Jujutsu's "Installation Help" group should show upgrade commands instead
 * of install commands (jj-idea-vwni). True for both "jj is too old" cases — Scenario A
 * ([FeatureAvailability.BelowMinimum]) and Scenario B ([FeatureAvailability.Gated]) — since jj is
 * genuinely installed and just needs updating in both; showing "if jj is not installed, use..."
 * for Scenario B was actively wrong, not just unhelpful. Derived from [featureAvailabilityFor]
 * rather than re-deriving the Scenario A/B split, so the two decisions can't drift apart.
 */
internal fun installHelpIsUpgradeFor(status: JjAvailabilityStatus): Boolean =
    when (featureAvailabilityFor(status)) {
        is FeatureAvailability.Gated, is FeatureAvailability.BelowMinimum -> true
        is FeatureAvailability.AllSupported, FeatureAvailability.Unknown -> false
    }

/** The [InstallMethod] jj was actually found via, when known — both "too old" statuses carry one. */
internal fun detectedInstallMethodFor(status: JjAvailabilityStatus): InstallMethod? =
    when (status) {
        is JjAvailabilityStatus.VersionTooOld -> status.installMethod
        is JjAvailabilityStatus.Available -> status.installMethod
        else -> null
    }
