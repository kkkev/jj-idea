package `in`.kkkev.jjidea.ui.services

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import `in`.kkkev.jjidea.jj.JjAvailabilityChecker
import `in`.kkkev.jjidea.jj.JjAvailabilityStatus
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.setup.JjUserConfigChecker
import `in`.kkkev.jjidea.util.runInBackground

/**
 * Bootstraps the Jujutsu state model and tool window management on project open.
 *
 * Initializing [ToolWindowEnabler] triggers [JujutsuStateModel][`in`.kkkev.jjidea.jj.JujutsuStateModel]
 * construction (via [stateModel][`in`.kkkev.jjidea.jj.stateModel]), which subscribes to IDE events
 * and fires the initial root scan. ToolWindowEnabler then connects to the root state to manage
 * tool window visibility and log tab suppression.
 *
 * Also initializes [JjAvailabilityChecker] to monitor jj availability and notify users
 * of issues (not found, wrong version, invalid path).
 */
class JujutsuStartupActivity : ProjectActivity {
    private val log = Logger.getInstance(javaClass)

    override suspend fun execute(project: Project) {
        ToolWindowEnabler.getInstance(project)

        // Wait for the repository cache to be populated before returning. IntelliJ activates VCS
        // (triggering annotation/diff providers) only after all ProjectActivity instances complete,
        // so this ensures jujutsuRepositoryFor() finds warm state rather than an empty map.
        // See NotifiableState.awaitLoad() for the ordering guarantee this relies on and what
        // would break it.
        project.stateModel.initialisedRepositories.awaitLoad()

        // Load project settings first — triggers migration of jjExecutablePath to app-level
        // settings. Must happen before the availability check reads from app settings.
        JujutsuSettings.getInstance(project)

        // Initialize availability checking
        val checker = JjAvailabilityChecker.getInstance(project)
        checker.status.connect(project) { status ->
            log.info("jj availability status changed: $status")
            when (status) {
                is JjAvailabilityStatus.Checking -> {} // Initial state, wait for real result
                is JjAvailabilityStatus.Available -> JujutsuNotifications.clearAvailabilityNotification()
                else -> JujutsuNotifications.notifyJjUnavailable(project, status)
            }
        }
        checker.recheck()

        // Check user config per repo as repos become available
        project.stateModel.initialisedRepositories.connect(project) { roots ->
            roots.values.forEach { repo -> checkUserConfigForRepo(project, repo) }
        }
    }

    private fun checkUserConfigForRepo(project: Project, repo: JujutsuRepository) = runInBackground {
        val checker = JjUserConfigChecker(repo)
        val config = checker.checkConfig()
        val name = repo.displayName
        log.info("User config check for $name: $config")

        if (!config.isComplete) {
            JujutsuNotifications.notifyUserConfigNeeded(project, repo.displayName)
        }
    }
}
