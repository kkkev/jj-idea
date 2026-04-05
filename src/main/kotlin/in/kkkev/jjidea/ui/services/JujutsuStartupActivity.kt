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
import java.util.concurrent.ConcurrentHashMap

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
        val checkedRepoPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()
        project.stateModel.initializedRoots.connect(project) { roots ->
            roots.forEach { repo ->
                if (checkedRepoPaths.add(repo.directory.path)) {
                    checkUserConfigForRepo(project, repo)
                }
            }
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
