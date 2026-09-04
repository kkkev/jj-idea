package `in`.kkkev.jjidea.preview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings

/**
 * Answers whether a [PreviewFeature] is currently enabled for this machine, per
 * `docs/design/preview-gating-and-dnd-sequencing.md`. Resolved through ordered providers,
 * default off:
 *
 * 1. System property `jjidea.preview.<id>=true` - dev/CI/platform-test escape hatch, not
 *    documented to users.
 * 2. Access code: [in.kkkev.jjidea.settings.JujutsuApplicationSettingsState.previewAccessCode]
 *    validates via [AccessCode], *and* the feature's id is in the user's opted-in feature set.
 * 3. *(future)* Licence, via a Marketplace freemium provider - a new step in this list, nothing
 *    else changes.
 *
 * Deliberately free of UI-internal dependencies, so this package can later become the API
 * surface a Marketplace freemium provider talks to without restructuring.
 */
@Service(Service.Level.APP)
class PreviewEntitlement {
    fun isEnabled(feature: PreviewFeature): Boolean {
        if (System.getProperty("jjidea.preview.${feature.id}").toBoolean()) return true

        val state = JujutsuApplicationSettings.getInstance().state
        val hasCode = AccessCode.isValid(state.previewAccessCode)
        val optedIn = state.enabledPreviewFeatures.split(",").map { it.trim() }.contains(feature.id)
        return hasCode && optedIn
    }

    companion object {
        fun getInstance(): PreviewEntitlement =
            ApplicationManager.getApplication().getService(PreviewEntitlement::class.java)
    }
}
