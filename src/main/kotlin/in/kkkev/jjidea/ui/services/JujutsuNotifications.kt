package `in`.kkkev.jjidea.ui.services

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.performAction
import `in`.kkkev.jjidea.jj.JjAvailabilityStatus
import `in`.kkkev.jjidea.jj.JjFeature
import `in`.kkkev.jjidea.jj.JjVersion
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationId
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.vcs.ignore.JujutsuIgnoreService
import java.awt.datatransfer.StringSelection
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

    // Track which roots we've already notified as unreadable, to avoid spamming
    private val notifiedUnreadableRoots = ConcurrentHashMap.newKeySet<String>()

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
            project.showVcsMappingsSettings()
        }

        notification.notify(project)
    }

    /**
     * Show a notification that a repository's `.jj` directory exists but jj could not read it —
     * a broken/stale store, a moved repo, or one created by an incompatible jj version
     * (jj-idea-9ife). Includes an action to reconfigure the VCS mapping; there's no "Initialize"
     * action since re-running init won't repair an already-broken repo.
     *
     * Only shows once per root per session to avoid notification spam.
     */
    fun notifyUnreadableRoot(project: Project, repo: JujutsuRepository, detail: String) {
        val rootPath = repo.directory.path

        // Only notify once per root
        if (!notifiedUnreadableRoots.add(rootPath)) {
            return
        }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                JujutsuBundle.message("notification.unreadable.title"),
                JujutsuBundle.message("notification.unreadable.content", repo.displayName, detail),
                NotificationType.WARNING
            )

        notification.addExpiringAction("notification.unreadable.action.retry") {
            notifiedUnreadableRoots.remove(rootPath)
            project.stateModel.invalidateRepositoryState()
        }

        notification.addExpiringAction("notification.unreadable.action.settings") {
            notifiedUnreadableRoots.remove(rootPath)
            project.showVcsMappingsSettings()
        }

        notification.notify(project)
    }

    /**
     * Clear the notification tracking for a root (e.g., after it's initialized or repaired).
     */
    fun clearNotificationState(rootPath: String) {
        notifiedUninitializedRoots.remove(rootPath)
        notifiedUnreadableRoots.remove(rootPath)
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

        if (status is JjAvailabilityStatus.Available || status is JjAvailabilityStatus.Checking) return

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

            is JjAvailabilityStatus.Available, is JjAvailabilityStatus.Checking -> return
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

    // Track repos for which a slow-scan notification has already been shown this session
    private val notifiedSlowScans: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val notifiedLargeIgnored: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Fires once per repo per session when the cached ignored-file entry count exceeds
     * [IGNORE_REPORT_CAP].  Offers the same "disable scanning" action as the slow-scan
     * notification so the user can silence both the scan cost and the warning.
     */
    fun notifyIgnoreScanLarge(project: Project, repo: JujutsuRepository, entryCount: Int) {
        if (!notifiedLargeIgnored.add(repo.directory.path)) return

        val repoName = repo.displayName
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                JujutsuBundle.message("notification.ignorescan.large.title"),
                JujutsuBundle.message("notification.ignorescan.large.content", repoName, entryCount),
                NotificationType.WARNING
            )

        notification.addExpiringAction("notification.ignorescan.action.disable") {
            JujutsuSettings.getInstance(project).setDisableIgnoredFileScanning(repo, true)
            JujutsuIgnoreService.getInstance(project).invalidate(repo)
            VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
        }

        notification.notify(project)
    }

    /**
     * Show a one-time-per-session warning when the ignored-file scan exceeds the watchdog budget.
     *
     * Offers two actions:
     * - Disable scanning for this repo (writes the per-repo setting and triggers a refresh)
     * - Report the issue (copies stats to clipboard and opens the GitHub issues page)
     */
    fun notifyIgnoreScanSlow(project: Project, repo: JujutsuRepository, elapsedMs: Long) {
        if (!notifiedSlowScans.add(repo.directory.path)) return

        val repoName = repo.displayName
        val stats = "$repoName: ${elapsedMs}ms"

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                JujutsuBundle.message("notification.ignorescan.slow.title"),
                JujutsuBundle.message("notification.ignorescan.slow.content", repoName),
                NotificationType.WARNING
            )

        notification.addExpiringAction("notification.ignorescan.action.disable") {
            JujutsuSettings.getInstance(project).setDisableIgnoredFileScanning(repo, true)
            JujutsuIgnoreService.getInstance(project).invalidate(repo)
            VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
        }

        notification.addExpiringAction("notification.ignorescan.action.report") {
            CopyPasteManager.getInstance().setContents(StringSelection(stats))
            BrowserUtil.browse("https://github.com/kkkev/jj-idea/issues")
        }

        notification.notify(project)
    }

    // Track which repo keys we've already notified about in this session (key = repoName ?: "global")
    private val notifiedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Show a notification prompting user to configure jj user settings.
     * Only shows once per repo per session. Pass [repoName] for per-repo notifications.
     * The action opens Settings → Version Control → Jujutsu where identity can be configured.
     */
    fun notifyUserConfigNeeded(project: Project, repoName: String? = null) {
        val key = repoName ?: "global"
        if (!notifiedKeys.add(key)) return

        val content = if (repoName != null) {
            JujutsuBundle.message("notification.userconfig.content.repo", repoName)
        } else {
            JujutsuBundle.message("notification.userconfig.content")
        }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                JujutsuBundle.message("notification.userconfig.title"),
                content,
                NotificationType.WARNING
            )

        notification.addExpiringAction("notification.userconfig.action.configure") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Jujutsu")
        }

        notification.notify(project)
    }

    /**
     * Show a one-time upgrade nudge (jj-idea-sov0) that jj [version] doesn't support
     * [gatedFeatures] yet. Never called for an unsupported/too-old jj — that's Scenario A,
     * handled entirely by [notifyJjUnavailable] — so this always uses [NotificationType.INFORMATION]:
     * nothing is broken, the user is just missing optional features.
     */
    fun notifyFeaturesGatedByVersion(project: Project, version: JjVersion, gatedFeatures: List<JjFeature>) {
        if (gatedFeatures.isEmpty()) return

        val list = gatedFeatures.joinToString("<br>") {
            JujutsuBundle.message("notification.feature.gated.item", it.displayName, it.minVersion.toString())
        }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                JujutsuBundle.message("notification.feature.gated.title"),
                JujutsuBundle.message("notification.feature.gated.content", version.toString(), list),
                NotificationType.INFORMATION
            )

        notification.addExpiringAction("notification.feature.gated.action.update") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Jujutsu")
        }

        // The shown-key is already persisted by the time this balloon appears (see
        // FeatureUpgradeNudge), so dismissal needs no side effect beyond expiring — the action
        // exists purely as an explicit "never again" affordance, same as SponsorAsk's dismiss.
        notification.addExpiringAction("notification.feature.gated.action.dismiss") {}

        notification.notify(project)
    }

    /**
     * Shows an INFORMATION balloon with an inline Undo link for a just-completed, reversible
     * [operation] in [repo] - the Stage 1 undo affordance
     * (docs/design/undo-support-roadmap.md). [description] names the action that was performed
     * (e.g. "Abandon"), matching the roadmap's requirement that an undo affordance says what it
     * targets rather than acting as a blind "Undo" button.
     */
    fun notifyUndoable(project: Project, repo: JujutsuRepository, operation: OperationId, description: String) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                JujutsuBundle.message("notification.undo.title"),
                JujutsuBundle.message("notification.undo.content", description),
                NotificationType.INFORMATION
            )

        notification.addExpiringAction("notification.undo.action") {
            performUndo(project, repo, operation)
        }

        notification.notify(project)
    }
}
