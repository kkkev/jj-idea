package `in`.kkkev.jjidea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

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
        // Migrate old default: users who never changed from 50 get bumped to 500
        if (state.settingsVersion < 1 && state.logChangeLimit == 50) {
            state.logChangeLimit = 500
        }
        state.settingsVersion = 1
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        fun getInstance(project: Project): JujutsuSettings = project.getService(JujutsuSettings::class.java)
    }
}
