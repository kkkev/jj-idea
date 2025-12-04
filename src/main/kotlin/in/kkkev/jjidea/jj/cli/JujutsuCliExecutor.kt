package `in`.kkkev.jjidea.jj.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.JujutsuCommandExecutor
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * CLI-based implementation of JujutsuCommandExecutor
 */
class JujutsuCliExecutor(private val root: VirtualFile, private val jjExecutable: String = "jj") :
    JujutsuCommandExecutor {

    private val log = Logger.getInstance(JujutsuCliExecutor::class.java)
    private val defaultTimeout = TimeUnit.SECONDS.toMillis(30)

    override fun status(revision: String?): JujutsuCommandExecutor.CommandResult {
        val args = mutableListOf("status")
        if (revision != null) {
            args.add("-r")
            args.add(revision)
        }
        return execute(root, args)
    }

    override fun diff(filePath: String): JujutsuCommandExecutor.CommandResult = execute(root, listOf("diff", filePath))

    override fun diffSummary(revision: String): JujutsuCommandExecutor.CommandResult =
        execute(root, listOf("diff", "--summary", "-r", revision))

    // TODO Not a change id here - need a revision type that could be change, bookmark or special token such as @
    override fun show(filePath: String, revision: String): JujutsuCommandExecutor.CommandResult =
        execute(root, listOf("file", "show", "-r", revision, filePath))

    override fun isAvailable(): Boolean = try {
        val result = execute(null, listOf("--version"))
        result.isSuccess
    } catch (e: Exception) {
        log.warn("Failed to check jj availability", e)
        false
    }

    override fun version(): String? = try {
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

    override fun describe(message: String, revision: String): JujutsuCommandExecutor.CommandResult =
        execute(root, listOf("describe", "-r", revision, "-m", message))

    override fun new(message: String?): JujutsuCommandExecutor.CommandResult {
        val args = mutableListOf("new")
        if (message != null) {
            args.add("-m")
            args.add(message)
        }
        return execute(root, args)
    }

    override fun log(revisions: String, template: String?, filePaths: List<String>): JujutsuCommandExecutor.CommandResult {
        val args = mutableListOf("log", "-r", revisions, "--no-graph")
        if (template != null) {
            args.add("-T")
            args.add(template)
        }
        args.addAll(filePaths)
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

        workingDir?.let { commandLine.setWorkDirectory(it.path) }

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