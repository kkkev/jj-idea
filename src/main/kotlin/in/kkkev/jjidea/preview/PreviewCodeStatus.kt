package `in`.kkkev.jjidea.preview

import java.time.LocalDate

/**
 * What the Settings access-code field should tell the user about the text currently in it.
 *
 * Distinct from [PreviewCode.Grant] because the settings panel needs one more case than the
 * codec has: a `JJP1` code is well-formed but this build ships no signing key
 * ([AccessCode.canVerifySignedCodes]), which is the build's problem rather than the user's typo
 * and must not read as "Not a valid access code".
 */
sealed interface PreviewCodeStatus {
    /** Features this status unlocks - empty for everything but [Accepted]. */
    val features: Set<PreviewFeature> get() = emptySet()

    /** Nothing entered. */
    data object Empty : PreviewCodeStatus

    data class Accepted(override val features: Set<PreviewFeature>, val expiry: LocalDate?) : PreviewCodeStatus

    data class Expired(val lastValidDate: LocalDate) : PreviewCodeStatus

    data object Revoked : PreviewCodeStatus

    /** A `JJP1` code on a build without the signing key (dev build, fork, or a release built without it). */
    data object CannotVerify : PreviewCodeStatus

    /** Unknown, malformed or tampered. */
    data object Invalid : PreviewCodeStatus

    /** True for the states the user needs to act on, so the UI can style them as problems. */
    val isProblem: Boolean get() = this !is Empty && this !is Accepted
}
