package `in`.kkkev.jjidea.jj

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Paths.get
import kotlin.io.path.pathString

private val pathsLog = Logger.getInstance("in.kkkev.jjidea.jj.paths")

internal val List<String>.paths
    get() = this.filterNot(String::isBlank).mapNotNull { entry ->
        try {
            Paths.get(entry)
        } catch (e: Exception) {
            pathsLog.warn("Skipping invalid PATH entry: $entry (${e.message})")
            null
        }
    }

/**
 * List of directories in the system path. Grab this once as it won't change during IntelliJ execution.
 */
internal val SYSTEM_PATH: List<Path> = System.getenv("PATH")
    ?.split(File.pathSeparator)
    ?.paths
    ?: emptyList()

internal val HOME: String = System.getProperty("user.home")

/**
 * Windows PATHEXT extensions (e.g., ".exe", ".cmd", ".bat").
 * Null on non-Windows systems.
 */
internal val PATHEXT: List<String>? = if (SystemInfo.isWindows) {
    System.getenv("PATHEXT")?.split(File.pathSeparator)?.filter { it.isNotBlank() }
} else {
    null
}

/**
 * Check if any of the given directories contain a file with the given name.
 * On Windows, also checks with PATHEXT extensions.
 */
internal fun List<Path?>.containsFile(fileName: String) = findFile(fileName) != null

/**
 * Find the first matching file in the given directories.
 * On Windows, also checks with PATHEXT extensions.
 */
internal fun List<Path?>.findFile(fileName: String): Path? = filterNotNull()
    .asSequence()
    .map { it.resolve(fileName) }
    .flatMap { resolvedPath ->
        PATHEXT?.map { ext -> get("${resolvedPath.pathString}$ext") } ?: listOf(resolvedPath)
    }
    .firstOrNull { Files.exists(it) && Files.isRegularFile(it) }

/**
 * Represents a method for installing or upgrading jj.
 *
 * Each method provides platform-specific commands and documentation URLs.
 */
sealed class InstallMethod(val name: String, val installCommand: String, val upgradeCommand: String) {
    /** Whether this install method can be run directly (vs copied to clipboard). */
    open val canRunDirectly: Boolean = false

    open val available: Boolean = false

    companion object {
        const val INSTALL_DOCS = "https://docs.jj-vcs.dev/latest/install-and-setup/"

        private val log = Logger.getInstance(InstallMethod::class.java)

        val allAvailable = try {
            when {
                SystemInfo.isMac -> listOf(Homebrew, Cargo, Manual)
                SystemInfo.isWindows -> listOf(Scoop, Winget, Chocolatey, Cargo, Manual)
                SystemInfo.isLinux -> listOfNotNull(Apt, Snap, Cargo, Manual)
                else -> listOf()
            }.filter { it.available }
        } catch (e: Exception) {
            log.debug("Error detecting available install methods", e)
            listOf(Manual)
        }
    }

    sealed class DetectableInstallMethod(
        name: String,
        commandToDetect: String,
        val searchPaths: List<Path?>,
        installCommand: String,
        upgradeCommand: String
    ) : InstallMethod(name, installCommand, upgradeCommand) {
        override val available = searchPaths.containsFile(commandToDetect)
    }

    // Mac
    data object Homebrew : DetectableInstallMethod(
        "Homebrew",
        "brew",
        SYSTEM_PATH + listOf("/opt/homebrew/bin", "/usr/local/bin").paths,
        "brew install jj",
        "brew upgrade jj"
    ) {
        override val canRunDirectly = true
    }

    // Windows
    data object Scoop : DetectableInstallMethod(
        "Scoop",
        "scoop",
        SYSTEM_PATH + listOfNotNull(System.getenv("SCOOP")?.let { get(it, "shims") }, get(HOME, "scoop", "shims")),
        "scoop install main/jj",
        "scoop update jj"
    )

    data object Chocolatey : DetectableInstallMethod(
        "Chocolatey",
        "choco",
        SYSTEM_PATH + System.getProperty("ChocolateyInstall")?.let { get(it) },
        "choco install jujutsu",
        "choco upgrade jujutsu"
    )

    data object Winget : DetectableInstallMethod(
        "Winget",
        "winget",
        SYSTEM_PATH,
        "winget install jj-vcs.jj",
        "winget upgrade jj-vcs.jj"
    )

    // Cross-platform
    data object Cargo : DetectableInstallMethod(
        "Cargo",
        "cargo",
        SYSTEM_PATH + get(HOME, ".cargo", "bin"),
        "cargo install --locked --bin jj jj-cli",
        "cargo install --locked --bin jj jj-cli"
    )

    // Linux
    data object Apt : DetectableInstallMethod(
        "APT",
        "apt",
        SYSTEM_PATH,
        "sudo apt install jj",
        "sudo apt update && sudo apt upgrade jj"
    )

    data object Snap : DetectableInstallMethod(
        "Snap",
        "snap",
        SYSTEM_PATH,
        "snap install jj",
        "snap refresh jj"
    )

    // Fallback
    data object Manual : InstallMethod("Manual", "", "") {
        override val available = true
    }

    /** Unknown install method (for executables found in PATH but not in known locations). */
    data object Unknown : InstallMethod("Unknown", "", "")
}
