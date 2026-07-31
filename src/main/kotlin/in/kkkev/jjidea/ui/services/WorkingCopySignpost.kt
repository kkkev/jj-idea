package `in`.kkkev.jjidea.ui.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.workingcopy.WorkingCopyToolWindowFactory
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * Directs new users to the "Working copy" tool window (jj-idea-jqpe, GitHub #56).
 *
 * Without this, new users discover the Working copy panel by accident or not at all,
 * and fall back to IntelliJ's standard Commit panel, which lacks jj-specific actions.
 *
 * Two independently-scoped, one-shot behaviours:
 * - Auto-opening the tool window (without stealing focus) is per-project: tool window
 *   layout is itself per-project state, so a new project deserves the panel visible.
 * - The explanatory balloon is per IDE install: once a user has seen it, re-showing it
 *   on every new project would just be nagging.
 */
object WorkingCopySignpost {
    private const val ONBOARDING_GROUP_ID = "Jujutsu Onboarding"

    fun signpostIfNeeded(project: Project) {
        runLater {
            if (project.isDisposed || !project.isJujutsu) return@runLater

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(
                WorkingCopyToolWindowFactory.TOOL_WINDOW_ID
            )
            if (toolWindow == null || !toolWindow.isAvailable) return@runLater

            val projectSettings = JujutsuSettings.getInstance(project)
            val appSettings = JujutsuApplicationSettings.getInstance()
            val actions = signpostActionsFor(
                hasJjRoots = true,
                autoOpened = projectSettings.state.workingCopyAutoOpened,
                balloonShown = appSettings.state.workingCopySignpostShown
            )

            if (actions.openToolWindow) {
                // autoFocusContents = false: signpost without stealing focus from the editor
                toolWindow.activate(null, false)
                projectSettings.state.workingCopyAutoOpened = true
            }

            if (actions.showBalloon) {
                showSignpostBalloon(project, toolWindow)
                appSettings.state.workingCopySignpostShown = true
            }
        }
    }

    private fun showSignpostBalloon(project: Project, toolWindow: ToolWindow) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(ONBOARDING_GROUP_ID)
            .createNotification(
                JujutsuBundle.message("notification.workingcopy.signpost.title"),
                JujutsuBundle.message("notification.workingcopy.signpost.content"),
                NotificationType.INFORMATION
            )

        notification.addExpiringAction("notification.workingcopy.signpost.action.open") {
            toolWindow.activate(null)
        }

        notification.notify(project)
    }
}

internal data class SignpostActions(val openToolWindow: Boolean, val showBalloon: Boolean)

internal fun signpostActionsFor(hasJjRoots: Boolean, autoOpened: Boolean, balloonShown: Boolean) =
    if (!hasJjRoots) {
        SignpostActions(openToolWindow = false, showBalloon = false)
    } else {
        SignpostActions(openToolWindow = !autoOpened, showBalloon = !balloonShown)
    }
