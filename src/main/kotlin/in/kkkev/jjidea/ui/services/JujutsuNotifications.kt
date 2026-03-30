package `in`.kkkev.jjidea.ui.services

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.performAction
import `in`.kkkev.jjidea.jj.JjAvailabilityStatus
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.setup.JjUserConfigChecker
import `in`.kkkev.jjidea.setup.JjUserConfigDialog
import java.util.concurrent.ConcurrentHashMap

fun Notification.addExpiringAction(messageKey: String, action: () -> Unit) {
    addAction(object : NotificationAction(JujutsuBundle.message(messageKey)) {
        override fun actionPerformed(e: AnActionEvent, notification: Notification) {
            notification.expire()
            action()
        }
    })
}

/**
 * Utility for showing Jujutsu-related notifications to the user.
 */
object JujutsuNotifications {
    private const val GROUP_ID = "Jujutsu"

    // Track which roots we've already notified about to avoid spamming
    private val notifiedUninitializedRoots = ConcurrentHashMap.newKeySet<String>()

    // Track current availability notification to avoid duplicates (global, not per-project)
    @Volatile
    private var currentAvailabilityNotification: Notification? = null

    // Track the last notified status to avoid duplicate notifications across projects
    @Volatile
    private var lastNotifiedStatus: JjAvailabilityStatus? = null

    /**
     * Show a notification that a VCS root is configured for Jujutsu but not initialized.
     * Includes actions to initialize or reconfigure the VCS mapping.
     *
     * Only shows once per root per session to avoid notification spam.
     */
    fun notifyUninitializedRoot(project: Project, repo: JujutsuRepository) {
        val rootPath = repo.directory.path

        // Only notify once per root
        if (!notifiedUninitializedRoots.add(rootPath)) {
            return
        }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                JujutsuBundle.message("notification.uninitialized.title"),
                JujutsuBundle.message("notification.uninitialized.content", repo.displayName),
                NotificationType.WARNING
            )

        // Action to initialize JJ - invoke registered action with directory context
        notification.addExpiringAction("notification.uninitialized.action.init") {
            notifiedUninitializedRoots.remove(rootPath)
            performAction("Jujutsu.Init", createDataContext(project, repo.directory))
        }

        // Action to open VCS settings
        notification.addExpiringAction("notification.uninitialized.action.settings") {
            notifiedUninitializedRoots.remove(rootPath)
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "project.propVCSSupport.Mappings")
        }

        notification.notify(project)
    }

    /**
     * Clear the notification tracking for a root (e.g., after it's initialized).
     */
    fun clearNotificationState(rootPath: String) {
        notifiedUninitializedRoots.remove(rootPath)
    }

    /**
     * Create a DataContext with project and file information.
     */
    private fun createDataContext(project: Project, file: VirtualFile) =
        CustomizedDataContext.withSnapshot(DataContext.EMPTY_CONTEXT) { sink ->
            sink[CommonDataKeys.PROJECT] = project
            sink[CommonDataKeys.VIRTUAL_FILE] = file
        }

    /**
     * Show a notification about jj availability issues.
     * Only shows one notification at a time to avoid spam.
     * Links to settings page for configuration.
     */
    fun notifyJjUnavailable(project: Project, status: JjAvailabilityStatus) {
        // Skip if we already have an active notification for the same status type
        if (currentAvailabilityNotification != null && isSameStatusType(lastNotifiedStatus, status)) {
            return
        }

        // Clear any existing availability notification
        clearAvailabilityNotification()

        if (status is JjAvailabilityStatus.Available) return

        val (title, content) = when (status) {
            is JjAvailabilityStatus.NotFound -> Pair(
                JujutsuBundle.message("notification.jj.notfound.title"),
                JujutsuBundle.message("notification.jj.notfound.content")
            )

            is JjAvailabilityStatus.VersionTooOld -> Pair(
                JujutsuBundle.message("notification.jj.version.title"),
                JujutsuBundle.message(
                    "notification.jj.version.content",
                    status.version.toString(),
                    status.minimumVersion.toString()
                )
            )

            is JjAvailabilityStatus.InvalidPath -> Pair(
                JujutsuBundle.message("notification.jj.invalid.title"),
                JujutsuBundle.message("notification.jj.invalid.content", status.configuredPath)
            )

            is JjAvailabilityStatus.Available -> return
        }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, NotificationType.WARNING)

        notification.addExpiringAction("notification.jj.action.configure") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Jujutsu")
        }

        // When notification is dismissed, clear the tracking so it can re-appear
        notification.whenExpired {
            if (currentAvailabilityNotification === notification) {
                currentAvailabilityNotification = null
                lastNotifiedStatus = null
            }
        }

        lastNotifiedStatus = status
        currentAvailabilityNotification = notification
        notification.notify(project)
    }

    private fun isSameStatusType(a: JjAvailabilityStatus?, b: JjAvailabilityStatus?): Boolean =
        when {
            a == null || b == null -> false
            a is JjAvailabilityStatus.NotFound && b is JjAvailabilityStatus.NotFound -> true
            a is JjAvailabilityStatus.VersionTooOld && b is JjAvailabilityStatus.VersionTooOld -> true
            a is JjAvailabilityStatus.InvalidPath && b is JjAvailabilityStatus.InvalidPath ->
                a.configuredPath == b.configuredPath

            else -> false
        }

    /**
     * Clear the current availability notification (e.g., when jj becomes available).
     */
    fun clearAvailabilityNotification() {
        currentAvailabilityNotification?.expire()
        currentAvailabilityNotification = null
        lastNotifiedStatus = null
    }

    /**
     * Show a simple notification with title and message.
     */
    fun notify(project: Project, title: String, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, message, type)
            .notify(project)
    }

    // Track if we've already notified about user config in this session
    @Volatile
    private var userConfigNotified = false

    /**
     * Show a notification prompting user to configure jj user settings.
     * Only shows once per session.
     */
    fun notifyUserConfigNeeded(project: Project, checker: JjUserConfigChecker) {
        if (userConfigNotified) return
        userConfigNotified = true

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                JujutsuBundle.message("notification.userconfig.title"),
                JujutsuBundle.message("notification.userconfig.content"),
                NotificationType.WARNING
            )

        notification.addExpiringAction("notification.userconfig.action.configure") {
            showUserConfigDialog(project, checker)
        }

        notification.notify(project)
    }

    private fun showUserConfigDialog(project: Project, checker: JjUserConfigChecker) {
        // Load git config on background, show dialog on EDT, save on background, notify on EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            val (gitName, gitEmail) = checker.getGitConfig()

            ApplicationManager.getApplication().invokeLater {
                val dialog = JjUserConfigDialog(project, gitName, gitEmail)
                if (dialog.showAndGet()) {
                    val config = dialog.result ?: return@invokeLater
                    ApplicationManager.getApplication().executeOnPooledThread {
                        val result = checker.setConfig(config.name, config.email)
                        ApplicationManager.getApplication().invokeLater {
                            val (title, message, type) = if (result?.isSuccess == true) {
                                Triple(
                                    JujutsuBundle.message("dialog.userconfig.success.title"),
                                    JujutsuBundle.message("dialog.userconfig.success.message"),
                                    NotificationType.INFORMATION
                                )
                            } else {
                                Triple(
                                    JujutsuBundle.message("dialog.userconfig.error.title"),
                                    JujutsuBundle.message(
                                        "dialog.userconfig.error.message",
                                        result?.stderr ?: "Unknown error"
                                    ),
                                    NotificationType.ERROR
                                )
                            }
                            notify(project, title, message, type)
                        }
                    }
                }
            }
        }
    }
}
