package `in`.kkkev.jjidea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.ui.log.JujutsuLogTableModel

/**
 * Persistent configuration for Jujutsu plugin.
 *
 * Access via: `project.getService(JujutsuSettings::class.java)`
 */
@Service(Service.Level.PROJECT)
@State(name = "JujutsuSettings", storages = [Storage("jujutsu.xml")])
class JujutsuSettings : PersistentStateComponent<JujutsuSettingsState> {
    private var state = JujutsuSettingsState()

    override fun getState(): JujutsuSettingsState = state

    override fun loadState(state: JujutsuSettingsState) {
        // Migration v1: users who never changed from 50 get bumped to 500
        if (state.settingsVersion < 1 && state.logChangeLimit == 50) {
            state.logChangeLimit = 500
        }

        // Migration v2: move custom jjExecutablePath to application-level settings
        if (state.settingsVersion < 2 && state.jjExecutablePath != "jj") {
            val appSettings = JujutsuApplicationSettings.getInstance()
            // Only migrate if app-level is still the default (first project wins)
            if (appSettings.state.jjExecutablePath == "jj") {
                appSettings.state.jjExecutablePath = state.jjExecutablePath
            }
            state.jjExecutablePath = "jj"
        }

        // Migration v3: int-keyed column widths → string-keyed
        if (state.settingsVersion < 3 && state.customLogColumnWidths.isNotEmpty() && state.columnWidths.isEmpty()) {
            val legacyMap = mapOf(
                0 to JujutsuLogTableModel.KEY_ROOT_GUTTER,
                1 to JujutsuLogTableModel.KEY_GRAPH_AND_DESCRIPTION,
                6 to JujutsuLogTableModel.KEY_AUTHOR,
                7 to JujutsuLogTableModel.KEY_COMMITTER,
                8 to JujutsuLogTableModel.KEY_DATE
            )
            for ((idx, width) in state.customLogColumnWidths) {
                legacyMap[idx]?.let { key -> state.columnWidths[key] = width }
            }
            state.customLogColumnWidths = mutableMapOf()
        }

        // Migration v4: create default log window, folding in any saved column widths
        if (state.settingsVersion < 4 && state.logWindows.isEmpty()) {
            state.logWindows.add(
                LogWindowConfig(
                    id = DEFAULT_LOG_WINDOW_ID,
                    name = JujutsuBundle.message("log.window.default.name"),
                    columnWidths = state.columnWidths.toMutableMap()
                )
            )
        }

        state.settingsVersion = 4
        XmlSerializerUtil.copyBean(state, this.state)
    }

    // ── Log window helpers ──────────────────────────────────────────────────────

    fun logWindows(): List<LogWindowConfig> = state.logWindows.toList()

    fun upsertLogWindow(config: LogWindowConfig) {
        val idx = state.logWindows.indexOfFirst { it.id == config.id }
        if (idx >= 0) state.logWindows[idx] = config else state.logWindows.add(config)
    }

    fun removeLogWindow(id: String) {
        state.logWindows.removeIf { it.id == id }
    }

    /**
     * Returns the persisted default window config, creating one if none exists yet.
     * The default window (id = [DEFAULT_LOG_WINDOW_ID]) is never closeable.
     */
    fun ensureDefaultWindow(): LogWindowConfig {
        val existing = state.logWindows.firstOrNull { it.id == DEFAULT_LOG_WINDOW_ID }
        if (existing != null) return existing
        val default = LogWindowConfig(
            id = DEFAULT_LOG_WINDOW_ID,
            name = JujutsuBundle.message("log.window.default.name")
        )
        state.logWindows.add(0, default)
        return default
    }

    // ── Per-repo helpers ────────────────────────────────────────────────────────

    /**
     * Returns the log change limit for a specific repository.
     * Falls back to the project-level default if no per-repo override exists.
     */
    fun logChangeLimit(repo: JujutsuRepository) =
        state.repositoryOverrides[repo.directory.path]?.logChangeLimit ?: state.logChangeLimit

    fun logRevset(repo: JujutsuRepository): String =
        state.repositoryOverrides[repo.directory.path]?.logRevset ?: state.logRevset

    companion object {
        const val DEFAULT_LOG_WINDOW_ID = "default"

        fun getInstance(project: Project): JujutsuSettings = project.getService(JujutsuSettings::class.java)
    }
}
