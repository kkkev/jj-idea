package `in`.kkkev.jjidea.jj.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.vcs.pathRelativeTo
import `in`.kkkev.jjidea.vcs.relativeTo
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal fun bookmarkCreateArgs(name: Bookmark, revision: Revision = WorkingCopy) =
    listOf("bookmark", "create", name, "-r", revision).map(Any::toString)

internal fun bookmarkDeleteArgs(name: Bookmark) = listOf("bookmark", "delete", name.toString())

internal fun bookmarkRenameArgs(oldName: Bookmark, newName: Bookmark) =
    listOf("bookmark", "rename", oldName, newName).map(Any::toString)

internal fun bookmarkSetArgs(name: Bookmark, revision: Revision = WorkingCopy, allowBackwards: Boolean = false) =
    buildList {
        addAll(listOf("bookmark", "set", name.toString(), "-r", revision.toString()))
        if (allowBackwards) add("-B")
    }

/** Build the argument list for `jj git fetch`. */
internal fun gitFetchArgs(remote: String? = null, allRemotes: Boolean = false): List<String> = buildList {
    add("git")
    add("fetch")
    if (allRemotes) {
        add("--all-remotes")
    } else if (remote != null) {
        add("--remote")
        add(remote)
    }
}

/** Build the argument list for `jj git push`. */
internal fun gitPushArgs(
    remote: String? = null,
    bookmark: String? = null,
    allBookmarks: Boolean = false
): List<String> = buildList {
    add("git")
    add("push")
    if (remote != null) {
        add("--remote")
        add(remote)
    }
    if (allBookmarks) {
        add("--all")
    } else if (bookmark != null) {
        add("--bookmark")
        add(bookmark)
    }
}

/** Build the argument list for `jj squash`. */
internal fun squashArgs(
    revision: Revision,
    filePaths: List<String> = emptyList(),
    description: Description? = null,
    keepEmptied: Boolean = false
): List<String> = buildList {
    add("squash")
    add("-r")
    add(revision.toString())
    if (description != null) {
        add("--message=${description.actual}")
    }
    if (keepEmptied) add("--keep-emptied")
    addAll(filePaths)
}

/** Build the argument list for `jj split`. */
internal fun splitArgs(
    revision: Revision,
    filePaths: List<String> = emptyList(),
    description: Description? = null,
    parallel: Boolean = false
): List<String> = buildList {
    add("split")
    add("-r")
    add(revision.toString())
    if (description != null) {
        add("--message=${description.actual}")
    }
    if (parallel) add("--parallel")
    addAll(filePaths)
}

/** Build the argument list for `jj git clone`. */
internal fun gitCloneArgs(source: String, destination: String, colocate: Boolean): List<String> = buildList {
    add("git")
    add("clone")
    if (colocate) add("--colocate") else add("--no-colocate")
    add(source)
    add(destination)
}

/** Build the argument list for `jj rebase`. */
internal fun rebaseArgs(
    revisions: List<Revision>,
    destinations: List<Revision>,
    sourceMode: RebaseSourceMode = RebaseSourceMode.REVISION,
    destinationMode: RebaseDestinationMode = RebaseDestinationMode.ONTO
): List<String> = buildList {
    add("rebase")
    revisions.forEach {
        add(sourceMode.flag)
        add(it.toString())
    }
    destinations.forEach {
        add(destinationMode.flag)
        add(it.toString())
    }
}

/**
 * CLI-based implementation of JujutsuCommandExecutor
 */
class CliExecutor(
    private val root: VirtualFile?,
    private val executableProvider: () -> String = { "jj" },
    private val onJjNotFound: (() -> Unit)? = null
) : CommandExecutor {
    private val log = Logger.getInstance(javaClass)
    private val defaultTimeout = TimeUnit.SECONDS.toMillis(30)
    private val networkTimeout = TimeUnit.SECONDS.toMillis(120)

    companion object {
        /** Pattern to extract percentage from git progress output (e.g., "Receiving objects:  45% (123/456)") */
        private val PROGRESS_PATTERN = Regex("""(\d+)%""")

        /**
         * Creates a CliExecutor for operations that don't require an existing repository
         * (e.g., gitClone, isAvailable, version).
         */
        fun forRootlessOperations(executableProvider: () -> String = { "jj" }) =
            CliExecutor(root = null, executableProvider = executableProvider)
    }

    override fun status() = execute(root, listOf("status"))

    override fun diff(filePath: String) = execute(root, listOf("diff", filePath))

    override fun diffSummary(revision: Revision) = execute(root, listOf("diff", "--summary", "-r", revision))

    override fun show(filePath: FilePath, revision: Revision) =
        execute(root, listOf("file", "show", "-r", revision, filePath.relativeTo(root!!)))

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
        execute(root, listOf("describe", "-r", revision, "--message=${description.actual}"))

    override fun new(description: Description, parentRevisions: List<Revision>): CommandExecutor.CommandResult {
        val args = mutableListOf("new")
        if (!description.empty) {
            args.add("--message=${description.actual}")
        }
        args.addAll(parentRevisions.map { it.toString() })
        return execute(root, args)
    }

    override fun abandon(revision: Revision): CommandExecutor.CommandResult =
        execute(root, listOf("abandon", "-r", revision))

    override fun edit(revision: Revision): CommandExecutor.CommandResult = execute(root, listOf("edit", revision))

    override fun log(
        revset: Revset,
        template: String?,
        filePaths: List<FilePath>,
        limit: Int?
    ): CommandExecutor.CommandResult {
        val args = mutableListOf<Any>("log", "-r", revset, "--no-graph")
        if (template != null) {
            args.add("-T")
            args.add(template)
        }
        if (limit != null) {
            args.add("--limit")
            args.add(limit)
        }
        args.addAll(filePaths.map { it.relativeTo(root!!) })
        return execute(root, args)
    }

    override fun annotate(file: VirtualFile, revision: Revision, template: String?): CommandExecutor.CommandResult {
        val args = mutableListOf("file", "annotate", "-r", revision)
        if (template != null) {
            args.add("-T")
            args.add(template)
        }
        args.add(file.pathRelativeTo(root!!))
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

    override fun bookmarkCreate(name: Bookmark, revision: Revision) = execute(root, bookmarkCreateArgs(name, revision))

    override fun bookmarkDelete(name: Bookmark) = execute(root, bookmarkDeleteArgs(name))

    override fun bookmarkRename(oldName: Bookmark, newName: Bookmark) =
        execute(root, bookmarkRenameArgs(oldName, newName))

    override fun bookmarkSet(name: Bookmark, revision: Revision, allowBackwards: Boolean) =
        execute(root, bookmarkSetArgs(name, revision, allowBackwards))

    override fun diffGit(revision: Revision): CommandExecutor.CommandResult =
        execute(root, listOf("diff", "--git", "-r", revision))

    override fun restore(filePaths: List<FilePath>, revision: Revision): CommandExecutor.CommandResult =
        execute(root, listOf("restore", "-f", revision) + filePaths.map { it.relativeTo(root!!) })

    override fun rebase(
        revisions: List<Revision>,
        destinations: List<Revision>,
        sourceMode: RebaseSourceMode,
        destinationMode: RebaseDestinationMode
    ) = execute(root, rebaseArgs(revisions, destinations, sourceMode, destinationMode))

    override fun squash(
        revision: Revision,
        filePaths: List<FilePath>,
        description: Description?,
        keepEmptied: Boolean
    ) = execute(root, squashArgs(revision, filePaths.map { it.relativeTo(root!!) }, description, keepEmptied))

    override fun split(
        revision: Revision,
        filePaths: List<FilePath>,
        description: Description?,
        parallel: Boolean
    ) = execute(root, splitArgs(revision, filePaths.map { it.relativeTo(root!!) }, description, parallel))

    override fun gitFetch(remote: String?, allRemotes: Boolean) =
        execute(root, gitFetchArgs(remote, allRemotes), timeout = networkTimeout)

    override fun gitPush(remote: String?, bookmark: String?, allBookmarks: Boolean) =
        execute(root, gitPushArgs(remote, bookmark, allBookmarks), timeout = networkTimeout)

    override fun gitRemoteList() = execute(root, listOf("git", "remote", "list"))

    override fun gitClone(source: String, destination: String, colocate: Boolean) =
        execute(null, gitCloneArgs(source, destination, colocate), timeout = networkTimeout)

    override fun configGet(key: String) = execute(null, listOf("config", "get", key))

    override fun configSetUser(key: String, value: String) =
        execute(null, listOf("config", "set", "--user", key, value))

    /**
     * Clone a Git repository with streaming progress updates.
     * Updates the progress indicator with clone status and percentage.
     */
    fun gitCloneWithProgress(
        source: String,
        destination: String,
        colocate: Boolean,
        indicator: ProgressIndicator
    ): CommandExecutor.CommandResult {
        val args = gitCloneArgs(source, destination, colocate)
        val executable = executableProvider()
        val commandLine = GeneralCommandLine(executable)
            .withParameters(args)
            .withCharset(StandardCharsets.UTF_8)

        commandLine.environment["NO_COLOR"] = "1"

        log.info("Executing: jj ${args.joinToString(" ")}")

        return try {
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val handler = OSProcessHandler(commandLine)
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text
                    if (text.isNotBlank()) {
                        if (outputType.toString() == "stderr") {
                            stderr.append(text)
                            updateProgress(indicator, text)
                        } else {
                            stdout.append(text)
                        }
                    }
                }
            })

            handler.startNotify()
            handler.waitFor(networkTimeout)

            val exitCode = handler.exitCode ?: -1

            log.info("Clone completed: exit=$exitCode")
            if (exitCode != 0) {
                log.warn("Clone failed: $stderr")
            }

            CommandExecutor.CommandResult(exitCode, stdout.toString(), stderr.toString())
        } catch (_: ProcessNotCreatedException) {
            log.warn("jj executable not found: $executable")
            onJjNotFound?.invoke()
            CommandExecutor.CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "jj executable not found: $executable. Please install jj or configure the path in Settings."
            )
        } catch (e: Exception) {
            log.error("Failed to execute jj git clone", e)
            CommandExecutor.CommandResult(-1, "", "Failed to execute jj: ${e.message}")
        }
    }

    private fun updateProgress(indicator: ProgressIndicator, text: String) {
        // Update progress text with the latest non-blank line
        text.lines().lastOrNull { it.isNotBlank() }?.let { line ->
            indicator.text2 = line.trim()
        }

        // Parse percentage from git progress output (e.g., "Receiving objects:  45% (123/456)")
        PROGRESS_PATTERN.find(text)?.let { match ->
            val percentage = match.groupValues[1].toIntOrNull()
            if (percentage != null) {
                indicator.isIndeterminate = false
                indicator.fraction = percentage / 100.0
            }
        }
    }

    private fun execute(
        workingDir: VirtualFile?,
        args: List<Any>,
        timeout: Long = defaultTimeout
    ): CommandExecutor.CommandResult {
        val executable = executableProvider()
        val commandLine = GeneralCommandLine(executable)
            .withParameters(args.map { it.toString() })
            .withCharset(StandardCharsets.UTF_8)

        workingDir?.let { commandLine.setWorkDirectory(it.path) }

        // Add color=never to avoid ANSI codes in output
        commandLine.environment["NO_COLOR"] = "1"

        val cmdName = args.firstOrNull()?.toString() ?: "unknown"
        log.info("Executing: jj $cmdName (${Thread.currentThread().name})")

        val startTime = System.currentTimeMillis()

        val processHandler = try {
            CapturingProcessHandler(commandLine)
        } catch (_: ProcessNotCreatedException) {
            // jj executable not found - return error result instead of throwing
            log.warn("jj executable not found: $executable")
            onJjNotFound?.invoke()
            return CommandExecutor.CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "jj executable not found: $executable. Please install jj or configure the path in Settings."
            )
        }

        val output: ProcessOutput = processHandler.runProcess(timeout.toInt())
        val duration = System.currentTimeMillis() - startTime

        log.info("Completed: jj $cmdName in ${duration}ms (exit=${output.exitCode})")

        output.takeIf { it.exitCode != 0 }?.run { log.warn("jj $cmdName failed with exit code $exitCode:\n$stderr") }

        return with(output) { CommandExecutor.CommandResult(exitCode, stdout, stderr) }
    }
}
