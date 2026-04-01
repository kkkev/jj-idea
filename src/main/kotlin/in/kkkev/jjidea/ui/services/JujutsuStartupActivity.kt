package `in`.kkkev.jjidea.ui.services

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import `in`.kkkev.jjidea.jj.JjAvailabilityChecker
import `in`.kkkev.jjidea.jj.JjAvailabilityStatus
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.setup.JjUserConfigChecker
import `in`.kkkev.jjidea.util.runInBackground
import java.nio.file.Path

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

        // Initialize availability checking
        val checker = JjAvailabilityChecker.getInstance(project)
        checker.status.connect(project) { status ->
            log.info("jj availability status changed: $status")
            when (status) {
                is JjAvailabilityStatus.Available -> {
                    JujutsuNotifications.clearAvailabilityNotification()
                    checkUserConfig(project, status.executablePath)
                }
                else -> JujutsuNotifications.notifyJjUnavailable(project, status)
            }
        }
        checker.recheck()
    }

    private fun checkUserConfig(project: Project, executablePath: Path) {
        val defaultPath = executablePath.toString()
        val executableProvider = {
            JujutsuSettings.getInstance(project).state.jjExecutablePath.ifBlank { defaultPath }
        }
        val executor = CliExecutor.forRootlessOperations(executableProvider)

        runInBackground {
            val checker = JjUserConfigChecker(executor)
            val config = checker.checkConfig()
            log.info("User config check: hasName=${config.hasName}, hasEmail=${config.hasEmail}")

            if (!config.isComplete) {
                JujutsuNotifications.notifyUserConfigNeeded(project, checker)
            }
        }
    }
}
