package `in`.kkkev.jjidea.settings

/**
 * Persistent state for Jujutsu plugin settings.
 *
 * [jjExecutablePath] is kept for migration deserialization only.
 * The canonical executable path lives in [JujutsuApplicationSettings].
 */
data class JujutsuSettingsState(
    var jjExecutablePath: String = "jj",
    var logChangeLimit: Int = 500,
    var logRevset: String = "all()",
    // Legacy int-keyed column widths kept for migration deserialization only (v3 migration)
    var customLogColumnWidths: MutableMap<Int, Int> = mutableMapOf(),
    // Global column widths kept for back-compat; v4 migration folds them into the default LogWindowConfig.
    var columnWidths: MutableMap<String, Int> = mutableMapOf(),
    var repositoryOverrides: MutableMap<String, RepositoryConfig> = mutableMapOf(),
    var settingsVersion: Int = 0,
    var squashDeleteEmptyAndMove: Boolean = false,
    var logWindows: MutableList<LogWindowConfig> = mutableListOf()
)
