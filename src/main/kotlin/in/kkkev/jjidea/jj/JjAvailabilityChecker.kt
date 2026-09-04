package `in`.kkkev.jjidea.jj

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings
import `in`.kkkev.jjidea.util.NotifiableState
import `in`.kkkev.jjidea.util.notifiableState

/**
 * Project-level service that checks jj availability and tracks status.
 *
 * Provides a [NotifiableState] that components can subscribe to for availability changes.
 * Status is checked when:
 * - It is first observed, via [NotifiableState.cachedValue] or [NotifiableState.connectAndFireSync]
 * - Settings change (executable path)
 * - [NotifiableState.invalidate] is called explicitly
 *
 * Use [status] to get the current availability status or subscribe to changes. Read it through
 * [jjAvailabilityStatus], and prefer `cachedValue`/`connectAndFireSync` over the raw `value`:
 * nothing checks availability on project open by itself, so a plain `value` read on a cold state
 * returns [JjAvailabilityStatus.Checking] indefinitely.
 */
@Service(Service.Level.PROJECT)
class JjAvailabilityChecker(private val project: Project) : Disposable {
    private val log = Logger.getInstance(javaClass)
    private val finder = JjExecutableFinder()

    /**
     * Current jj availability status.
     * Starts as [JjAvailabilityStatus.Checking] and is updated asynchronously once loaded.
     */
    val status: NotifiableState<JjAvailabilityStatus> = notifiableState(
        project,
        "JjAvailabilityStatus",
        JjAvailabilityStatus.Checking
    ) {
        checkAvailability()
    }

    private fun checkAvailability(): JjAvailabilityStatus {
        try {
            val configuredPath = JujutsuApplicationSettings.getInstance().state.jjExecutablePath

            log.info("Checking jj availability (configured path: $configuredPath)")

            // If custom path configured, validate it specifically
            if (configuredPath != "jj" && configuredPath.isNotBlank()) {
                return validateConfiguredPath(configuredPath)
            }

            // Otherwise, search all known locations
            val best = finder.findBestExecutable(configuredPath)
            if (best != null) {
                log.info("Found suitable jj: ${best.path} (version ${best.version})")
                return JjAvailabilityStatus.Available(best.path, best.version, best.installMethod)
            }

            // Check if any jj exists but doesn't meet version
            val anyJj = finder.findAnyExecutable(configuredPath)
            if (anyJj != null) {
                log.info("Found jj but version too old: ${anyJj.version} < ${JjVersion.MINIMUM}")
                return JjAvailabilityStatus.VersionTooOld(
                    executablePath = anyJj.path,
                    version = anyJj.version,
                    minimumVersion = JjVersion.MINIMUM,
                    installMethod = anyJj.installMethod,
                    availableMethods = InstallMethod.allAvailable
                )
            }

            // Not found at all
            log.info("jj not found")
            return JjAvailabilityStatus.NotFound(availableMethods = InstallMethod.allAvailable)
        } catch (e: Exception) {
            log.warn("Error checking jj availability", e)
            return JjAvailabilityStatus.NotFound(availableMethods = InstallMethod.allAvailable)
        }
    }

    private fun validateConfiguredPath(path: String): JjAvailabilityStatus =
        when (val result = finder.validatePath(path)) {
            is JjExecutableFinder.ValidationResult.Valid -> {
                val exe = result.executable
                if (exe.version.meetsMinimum()) {
                    JjAvailabilityStatus.Available(exe.path, exe.version, exe.installMethod)
                } else {
                    JjAvailabilityStatus.VersionTooOld(
                        executablePath = exe.path,
                        version = exe.version,
                        minimumVersion = JjVersion.MINIMUM,
                        installMethod = exe.installMethod,
                        availableMethods = InstallMethod.allAvailable
                    )
                }
            }

            is JjExecutableFinder.ValidationResult.Invalid -> {
                log.warn("Configured jj path invalid: $path (${result.reason})")
                JjAvailabilityStatus.InvalidPath(path, result.reason, InstallMethod.allAvailable)
            }
        }

    override fun dispose() {
        // Nothing to dispose
    }
}

val Project.jjAvailabilityStatus get() = service<JjAvailabilityChecker>().status

/** Triggers a first check if the status hasn't loaded yet — see [NotifiableState.cachedValue]. */
val NotifiableState<JjAvailabilityStatus>.isAvailable get() = this.cachedValue is JjAvailabilityStatus.Available
