package `in`.kkkev.jjidea.commands

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * CLI-based implementation of JujutsuCommandExecutor
 */
class JujutsuCliExecutor(
    private val jjExecutable: String = "jj"
) : JujutsuCommandExecutor {

    private val log = Logger.getInstance(JujutsuCliExecutor::class.java)
    private val defaultTimeout = TimeUnit.SECONDS.toMillis(30)

    override fun status(root: VirtualFile): JujutsuCommandExecutor.CommandResult {
        return execute(root, listOf("status"))
    }

    override fun diff(root: VirtualFile, filePath: String): JujutsuCommandExecutor.CommandResult {
        return execute(root, listOf("diff", filePath))
    }

    override fun show(root: VirtualFile, filePath: String, revision: String): JujutsuCommandExecutor.CommandResult {
        return execute(root, listOf("file", "show", "-r", revision, filePath))
    }

    override fun isAvailable(): Boolean {
        return try {
            val result = execute(null, listOf("--version"))
            result.isSuccess
        } catch (e: Exception) {
            log.warn("Failed to check jj availability", e)
            false
        }
    }

    override fun version(): String? {
        return try {
            val result = execute(null, listOf("--version"))
            if (result.isSuccess) {
                result.stdout.trim()
            } else {
                null
            }
        } catch (e: Exception) {
            log.warn("Failed to get jj version", e)
            null
        }
    }

    override fun describe(root: VirtualFile, message: String, revision: String): JujutsuCommandExecutor.CommandResult {
        return execute(root, listOf("describe", "-r", revision, "-m", message))
    }

    override fun new(root: VirtualFile, message: String?): JujutsuCommandExecutor.CommandResult {
        val args = mutableListOf("new")
        if (message != null) {
            args.add("-m")
            args.add(message)
        }
        return execute(root, args)
    }

    override fun log(root: VirtualFile, revisions: String, template: String?): JujutsuCommandExecutor.CommandResult {
        val args = mutableListOf("log", "-r", revisions, "--no-graph")
        if (template != null) {
            args.add("-T")
            args.add(template)
        }
        return execute(root, args)
    }

    private fun execute(
        workingDir: VirtualFile?,
        args: List<String>,
        timeout: Long = defaultTimeout
    ): JujutsuCommandExecutor.CommandResult {
        val commandLine = GeneralCommandLine(jjExecutable)
            .withParameters(args)
            .withCharset(StandardCharsets.UTF_8)

        workingDir?.let {
            commandLine.setWorkDirectory(it.path)
        }

        // Add color=never to avoid ANSI codes in output
        commandLine.environment["NO_COLOR"] = "1"

        log.debug("Executing: ${commandLine.commandLineString}")

        val processHandler = CapturingProcessHandler(commandLine)
        val output: ProcessOutput = processHandler.runProcess(timeout.toInt())

        return JujutsuCommandExecutor.CommandResult(
            exitCode = output.exitCode,
            stdout = output.stdout,
            stderr = output.stderr
        )
    }
}
