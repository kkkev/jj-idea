package `in`.kkkev.jjidea.settings

/**
 * Persistent state for Jujutsu plugin settings.
 */
data class JujutsuSettingsState(
    var jjExecutablePath: String = "jj",
    var autoRefreshEnabled: Boolean = true,
    var showChangeIdsInShortFormat: Boolean = true,
    var logChangeLimit: Int = 50,
    var autoOpenCustomLogTab: Boolean = true,

    // Column widths for custom log table (column index -> width in pixels)
    var customLogColumnWidths: MutableMap<Int, Int> = mutableMapOf()
)
