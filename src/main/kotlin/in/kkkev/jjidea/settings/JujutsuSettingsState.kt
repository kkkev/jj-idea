package `in`.kkkev.jjidea.settings

/**
 * Persistent state for Jujutsu plugin settings.
 *
 * [jjExecutablePath] is kept for migration deserialization only.
 * The canonical executable path lives in [JujutsuApplicationSettings].
 */
data class JujutsuSettingsState(
    var jjExecutablePath: String = "jj",
    var autoRefreshEnabled: Boolean = true,
    var showChangeIdsInShortFormat: Boolean = true,
    var logChangeLimit: Int = 500,
    var logRevset: String = "all()",
    var autoOpenCustomLogTab: Boolean = true,
    // Column widths for custom log table (column index -> width in pixels)
    var customLogColumnWidths: MutableMap<Int, Int> = mutableMapOf(),
    var repositoryOverrides: MutableMap<String, RepositoryConfig> = mutableMapOf(),
    var settingsVersion: Int = 0
)
