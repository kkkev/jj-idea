package `in`.kkkev.jjidea.jj

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle

/**
 * A plugin feature that depends on a `jj` CLI version newer than [JjVersion.MINIMUM].
 *
 * Actions gated on a [JjFeature] should stay **visible but disabled** when unsupported, with
 * [disabledReasonIn] explaining why via `AnActionEvent.presentation.description` — never hidden,
 * and never emulated with a lower-version workaround. See contributing.md and jj-idea-asap for
 * the rationale: a hidden action is indistinguishable from a bug, and a workaround diverges from
 * what the CLI actually supports.
 */
enum class JjFeature(val minVersion: JjVersion, private val displayNameKey: String) {
    /** `jj bookmark advance`, added in jj 0.39.0. */
    BOOKMARK_ADVANCE(JjVersion(0, 39, 0), "feature.bookmark.advance.name");

    /** User-facing name for this feature, e.g. for a settings list or upgrade nudge. */
    val displayName: String get() = JujutsuBundle.message(displayNameKey)
}

/** Whether this version supports [feature]. */
fun JjVersion.supports(feature: JjFeature) = this >= feature.minVersion

/** Features [version] is too old for, in declaration order. Empty when it supports everything. */
fun unsupportedFeatures(version: JjVersion): List<JjFeature> = JjFeature.entries.filter { !version.supports(it) }

/**
 * Features the currently detected jj is too old for. Empty unless [status] is
 * [JjAvailabilityStatus.Available] — Scenario A (VersionTooOld/NotFound/InvalidPath) already has
 * its own notification and must not be double-reported here.
 */
fun unsupportedFeatures(status: JjAvailabilityStatus): List<JjFeature> =
    when (status) {
        is JjAvailabilityStatus.Available -> unsupportedFeatures(status.version)
        else -> emptyList()
    }

/**
 * The currently detected jj version, or `null` if jj isn't available (in which case every
 * feature-gated action is disabled anyway via the normal jj-unavailable handling).
 */
val JjAvailabilityStatus.versionOrNull: JjVersion?
    get() = when (this) {
        is JjAvailabilityStatus.Available -> version
        is JjAvailabilityStatus.VersionTooOld -> version
        else -> null
    }

/**
 * Whether jj as currently detected in [project] supports [feature]. `false` (not gated-enabled)
 * when jj's version can't be determined yet.
 */
fun JjFeature.isSupportedIn(project: Project): Boolean =
    JjAvailabilityChecker.getInstance(project).status.value.versionOrNull?.supports(this) ?: false

/**
 * Standard "requires jj X.Y.Z" description for a disabled, version-gated
 * [com.intellij.openapi.actionSystem.Presentation]. `null` when jj's version can't be determined
 * yet (nothing useful to say beyond the normal jj-unavailable handling) or when [feature] is
 * already supported.
 */
fun JjFeature.disabledReasonIn(project: Project): String? {
    val version = JjAvailabilityChecker.getInstance(project).status.value.versionOrNull ?: return null
    if (version.supports(this)) return null
    return JujutsuBundle.message("action.disabled.requires.jj.version", minVersion, version)
}
