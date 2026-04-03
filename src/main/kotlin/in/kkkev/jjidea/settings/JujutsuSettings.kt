package `in`.kkkev.jjidea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import `in`.kkkev.jjidea.jj.JujutsuRepository

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

        state.settingsVersion = 2
        XmlSerializerUtil.copyBean(state, this.state)
    }

    /**
     * Returns the log change limit for a specific repository.
     * Falls back to the project-level default if no per-repo override exists.
     */
    fun logChangeLimit(repo: JujutsuRepository) =
        state.repositoryOverrides[repo.directory.path]?.logChangeLimit ?: state.logChangeLimit

    companion object {
        fun getInstance(project: Project): JujutsuSettings = project.getService(JujutsuSettings::class.java)
    }
}
