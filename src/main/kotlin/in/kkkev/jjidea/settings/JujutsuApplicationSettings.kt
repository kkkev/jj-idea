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
    var jjExecutablePath: String = "jj",
    // jj-idea-jqpe: tracks whether the one-time "Working copy" tool window signpost balloon
    // has already been shown on this machine, across all projects.
    var workingCopySignpostShown: Boolean = false,
    // jj-idea-ixju: global default for disableIgnoredFileScanning, applied to any repository
    // that doesn't set its own per-repo override (see JujutsuSettings.disableIgnoredFileScanning).
    var disableIgnoredFileScanning: Boolean = false,
    // jj-idea-z1ld: epoch millis of the first time a jj project was opened on this machine.
    // 0 means "not yet recorded"; used to gate the sponsor ask on sustained use.
    var firstRunEpochMillis: Long = 0,
    // jj-idea-z1ld: the one-time sponsor ask has been shown (or explicitly dismissed) on this
    // machine. Set when the balloon is shown, so it never appears twice.
    var sponsorAskShown: Boolean = false,
    // jj-idea-sov0: key of the (jj version, gated feature set) combination the version-gated
    // upgrade nudge has already been shown for, e.g. "0.38.0|BOOKMARK_ADVANCE". Empty means
    // never shown. Keyed on both halves so the nudge reappears when the user's jj version
    // changes AND when a plugin update newly gates a feature at the same jj version.
    var featureNudgeShownKey: String = "",
    // jj-idea-vpvz: the preview-feature access code as entered in Settings; validated offline
    // against in.kkkev.jjidea.preview.AccessCode. Empty means no code entered.
    var previewAccessCode: String = "",
    // jj-idea-vpvz: comma-separated PreviewFeature ids the user has opted into, only meaningful
    // while previewAccessCode validates. See in.kkkev.jjidea.preview.PreviewEntitlement.
    var enabledPreviewFeatures: String = ""
)
