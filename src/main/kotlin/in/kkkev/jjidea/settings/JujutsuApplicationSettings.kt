package `in`.kkkev.jjidea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level settings for Jujutsu plugin.
 *
 * Stores machine-specific settings that should be shared across all projects,
 * such as the jj executable path.
 */
@Service(Service.Level.APP)
@State(name = "JujutsuApplicationSettings", storages = [Storage("jujutsu.xml", roamingType = RoamingType.DISABLED)])
class JujutsuApplicationSettings : PersistentStateComponent<JujutsuApplicationSettingsState> {
    private var state = JujutsuApplicationSettingsState()

    override fun getState(): JujutsuApplicationSettingsState = state

    override fun loadState(state: JujutsuApplicationSettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        fun getInstance(): JujutsuApplicationSettings =
            ApplicationManager.getApplication().getService(JujutsuApplicationSettings::class.java)
    }
}

data class JujutsuApplicationSettingsState(
    var jjExecutablePath: String = "jj"
)
