package `in`.kkkev.jjidea.jj

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.cli.CliExecutor
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings

/**
 * Service for creating command executors for specific repository directories.
 */
interface CommandExecutorFactory {
    fun create(directory: VirtualFile): CommandExecutor
}

val Project.commandExecutorFactory: CommandExecutorFactory get() = getService(CliExecutorFactory::class.java)

@Service(Service.Level.PROJECT)
class CliExecutorFactory(private val project: Project) : CommandExecutorFactory {
    override fun create(directory: VirtualFile) = CliExecutor(
        root = directory,
        executableProvider = { JujutsuApplicationSettings.getInstance().state.jjExecutablePath },
        onJjNotFound = { JjAvailabilityChecker.getInstance(project).recheck() }
    )
}
