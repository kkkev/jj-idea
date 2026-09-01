package `in`.kkkev.jjidea.ui.services

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.JjAvailabilityStatus
import `in`.kkkev.jjidea.jj.JjFeature
import `in`.kkkev.jjidea.jj.JjVersion
import `in`.kkkev.jjidea.jj.unsupportedFeatures
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * A single, one-time-per-(jj version, gated feature set) upgrade nudge (jj-idea-sov0,
 * surface 2 of jj-idea-xuah).
 *
 * Scenario A (jj below [JjVersion.MINIMUM]) already has its own startup balloon
 * ([JujutsuNotifications.notifyJjUnavailable]); this only covers Scenario B — jj meets the
 * minimum but is too old for one or more [JjFeature]s. Reuses [unsupportedFeatures], whose
 * status-driven overload already returns an empty list for every non-[JjAvailabilityStatus.Available]
 * status, so Scenario A can never trigger this nudge.
 */
object FeatureUpgradeNudge {
    fun nudgeIfNeeded(project: Project, status: JjAvailabilityStatus) {
        runLater {
            if (project.isDisposed || !project.isJujutsu) return@runLater

            val state = JujutsuApplicationSettings.getInstance().state
            val nudge = featureNudgeActionFor(status, state.featureNudgeShownKey) ?: return@runLater

            JujutsuNotifications.notifyFeaturesGatedByVersion(project, nudge.version, nudge.gatedFeatures)
            state.featureNudgeShownKey = nudge.key
        }
    }
}

internal data class FeatureNudge(val version: JjVersion, val gatedFeatures: List<JjFeature>, val key: String)

/**
 * Pure show/skip decision: `null` means don't nudge — either jj isn't [JjAvailabilityStatus.Available],
 * every [JjFeature] is supported, or this exact (version, gated feature set) combination was
 * already shown.
 */
internal fun featureNudgeActionFor(status: JjAvailabilityStatus, shownKey: String): FeatureNudge? {
    val version = (status as? JjAvailabilityStatus.Available)?.version ?: return null
    val gated = unsupportedFeatures(status)
    if (gated.isEmpty()) return null

    val key = "$version|" + gated.joinToString(",") { it.name }
    if (key == shownKey) return null

    return FeatureNudge(version, gated, key)
}
