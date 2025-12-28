package `in`.kkkev.jjidea.settings

/**
 * Persistent state for Jujutsu plugin settings.
 */
data class JujutsuSettingsState(
    var jjExecutablePath: String = "jj",
    var autoRefreshEnabled: Boolean = true,
    var showChangeIdsInShortFormat: Boolean = true,
    var logChangeLimit: Int = 50,
    var autoOpenCustomLogTab: Boolean = true
)
