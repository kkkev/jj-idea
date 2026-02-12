package `in`.kkkev.jjidea.jj.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.Revset
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * CLI-based implementation of JujutsuCommandExecutor
 */
class CliExecutor(
    private val root: VirtualFile,
    private val jjExecutable: String = "jj"
) : CommandExecutor {
    private val log = Logger.getInstance(javaClass)
    private val defaultTimeout = TimeUnit.SECONDS.toMillis(30)

    override fun status() = execute(root, listOf("status"))

    override fun diff(filePath: String) = execute(root, listOf("diff", filePath))

    override fun diffSummary(revision: Revision) = execute(root, listOf("diff", "--summary", "-r", revision))

    override fun show(filePath: String, revision: Revision) =
        execute(root, listOf("file", "show", "-r", revision, filePath))

    override fun isAvailable() = try {
        val result = execute(null, listOf("--version"))
        result.isSuccess
    } catch (e: Exception) {
        log.warn("Failed to check jj availability", e)
        false
    }

    override fun version() = try {
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

    override fun gitInit(colocate: Boolean) =
        execute(root, listOfNotNull("git", "init", "--colocate".takeIf { colocate }))

    override fun describe(description: Description, revision: Revision) =
        execute(root, listOf("describe", "-r", revision, "-m", description.actual))

    override fun new(description: Description, parentRevisions: List<Revision>): CommandExecutor.CommandResult {
        val args = mutableListOf("new")
        if (!description.empty) {
            args.add("-m")
            args.add(description.actual)
        }
        args.addAll(parentRevisions.map { it.toString() })
        return execute(root, args)
    }

    override fun abandon(revision: Revision): CommandExecutor.CommandResult =
        execute(root, listOf("abandon", "-r", revision))

    override fun edit(revision: Revision): CommandExecutor.CommandResult = execute(root, listOf("edit", revision))

    override fun log(revset: Revset, template: String?, filePaths: List<String>): CommandExecutor.CommandResult {
        val args = mutableListOf("log", "-r", revset, "--no-graph")
        if (template != null) {
            args.add("-T")
            args.add(template)
        }
        args.addAll(filePaths)
        return execute(root, args)
    }

    override fun annotate(filePath: String, revision: Revision, template: String?): CommandExecutor.CommandResult {
        val args = mutableListOf("file", "annotate", "-r", revision)
        if (template != null) {
            args.add("-T")
            args.add(template)
        }
        args.add(filePath)
        return execute(root, args)
    }

    override fun bookmarkList(template: String?): CommandExecutor.CommandResult {
        val args = mutableListOf("bookmark", "list")
        if (template != null) {
            args.add("-T")
            args.add(template)
        }
        return execute(root, args)
    }

    override fun diffGit(revision: Revision): CommandExecutor.CommandResult =
        execute(root, listOf("diff", "--git", "-r", revision))

    override fun restore(filePaths: List<String>, revision: Revision): CommandExecutor.CommandResult =
        execute(root, listOf("restore", "-f", revision) + filePaths)

    private fun execute(
        workingDir: VirtualFile?,
        args: List<Any>,
        timeout: Long = defaultTimeout
    ): CommandExecutor.CommandResult {
        val commandLine = GeneralCommandLine(jjExecutable)
            .withParameters(args.map { it.toString() })
            .withCharset(StandardCharsets.UTF_8)

        workingDir?.let { commandLine.setWorkDirectory(it.path) }

        // Add color=never to avoid ANSI codes in output
        commandLine.environment["NO_COLOR"] = "1"

        val cmdName = args.firstOrNull()?.toString() ?: "unknown"
        log.info("Executing: jj $cmdName (${Thread.currentThread().name})")

        val startTime = System.currentTimeMillis()
        val processHandler = CapturingProcessHandler(commandLine)
        val output: ProcessOutput = processHandler.runProcess(timeout.toInt())
        val duration = System.currentTimeMillis() - startTime

        log.info("Completed: jj $cmdName in ${duration}ms (exit=${output.exitCode})")

        return CommandExecutor.CommandResult(exitCode = output.exitCode, stdout = output.stdout, stderr = output.stderr)
    }
}
