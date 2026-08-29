package `in`.kkkev.jjidea.ui.services

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.isJujutsu
import kotlin.time.Duration.Companion.days

/** Public so [in.kkkev.jjidea.settings.JujutsuConfigurable]'s Support link can share it. */
const val SPONSORS_URL = "https://github.com/sponsors/kkkev"

/** Minimum time since first run before the sponsor ask is shown. */
private val SPONSOR_ASK_DELAY = 14.days

/**
 * A single, non-intrusive in-product sponsor ask (jj-idea-z1ld).
 *
 * Fires at most once per IDE install, and only after sustained use — never on first run,
 * never per-project. Reuses the "Jujutsu Onboarding" notification group that
 * [WorkingCopySignpost] uses, and is triggered from the same startup activity.
 */
object SponsorAsk {
    private const val ONBOARDING_GROUP_ID = "Jujutsu Onboarding"

    fun askIfNeeded(project: Project) {
        runLater {
            if (project.isDisposed || !project.isJujutsu) return@runLater

            val appSettings = JujutsuApplicationSettings.getInstance()
            val state = appSettings.state
            val actions = sponsorAskActionsFor(
                nowMillis = System.currentTimeMillis(),
                firstRunEpochMillis = state.firstRunEpochMillis,
                askShown = state.sponsorAskShown
            )

            if (actions.recordFirstRun) {
                state.firstRunEpochMillis = System.currentTimeMillis()
            }

            if (actions.showAsk) {
                showSponsorBalloon(project)
                state.sponsorAskShown = true
            }
        }
    }

    private fun showSponsorBalloon(project: Project) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(ONBOARDING_GROUP_ID)
            .createNotification(
                JujutsuBundle.message("notification.sponsor.title"),
                JujutsuBundle.message("notification.sponsor.content"),
                NotificationType.INFORMATION
            )

        notification.addExpiringAction("notification.sponsor.action.sponsor") {
            BrowserUtil.browse(SPONSORS_URL)
        }
        // jj-idea-z1ld: the flag is already set by the time this balloon is shown, so
        // dismissal doesn't need to do anything beyond expiring the notification — the
        // action exists purely to give the user an explicit "never again" affordance.
        notification.addExpiringAction("notification.sponsor.action.dismiss") {}

        notification.notify(project)
    }
}

internal data class SponsorAskActions(val recordFirstRun: Boolean, val showAsk: Boolean)

internal fun sponsorAskActionsFor(nowMillis: Long, firstRunEpochMillis: Long, askShown: Boolean) =
    if (firstRunEpochMillis == 0L) {
        // Never on first run: just record it, so the delay starts counting from now.
        SponsorAskActions(recordFirstRun = true, showAsk = false)
    } else {
        val elapsedMillis = nowMillis - firstRunEpochMillis
        val showAsk = !askShown && elapsedMillis in SPONSOR_ASK_DELAY.inWholeMilliseconds..Long.MAX_VALUE
        SponsorAskActions(recordFirstRun = false, showAsk = showAsk)
    }
