package `in`.kkkev.jjidea.jj

import java.nio.file.Path

/**
 * Status of jj availability on the system.
 *
 * Provides detailed information about why jj is or isn't available,
 * and what actions the user can take to resolve issues.
 */
sealed interface JjAvailabilityStatus {
    /**
     * Availability check is in progress. Shown briefly at startup before the first check completes.
     */
    data object Checking : JjAvailabilityStatus

    /**
     * jj found and ready to use.
     */
    data class Available(
        val executablePath: Path,
        val version: JjVersion,
        val installMethod: InstallMethod
    ) : JjAvailabilityStatus

    /**
     * jj found but version is too old.
     */
    data class VersionTooOld(
        val executablePath: Path,
        val version: JjVersion,
        val minimumVersion: JjVersion,
        val installMethod: InstallMethod,
        val availableMethods: List<InstallMethod>
    ) : JjAvailabilityStatus

    /**
     * User-configured path is invalid.
     */
    data class InvalidPath(
        val configuredPath: String,
        val reason: JjExecutableFinder.InvalidReason,
        val availableMethods: List<InstallMethod>
    ) : JjAvailabilityStatus

    /**
     * jj not found anywhere.
     */
    data class NotFound(
        val availableMethods: List<InstallMethod>
    ) : JjAvailabilityStatus
}
