package `in`.kkkev.jjidea.setup

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import `in`.kkkev.jjidea.jj.CommandExecutor
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Checks and configures jj user settings (user.name, user.email).
 *
 * Used during initial setup to detect missing user configuration and pre-populate
 * values from git global config if available.
 */
class JjUserConfigChecker(private val executor: CommandExecutor) {
    private val log = Logger.getInstance(javaClass)

    /**
     * Configuration status for jj user settings.
     */
    data class ConfigStatus(val hasName: Boolean, val hasEmail: Boolean, val name: String?, val email: String?) {
        val isComplete: Boolean get() = hasName && hasEmail
    }

    /**
     * Check current jj user configuration.
     * Call from background thread.
     */
    fun checkConfig(): ConfigStatus {
        val nameResult = executor.configGet("user.name")
        val emailResult = executor.configGet("user.email")

        return ConfigStatus(
            hasName = nameResult.isSuccess && nameResult.stdout.isNotBlank(),
            hasEmail = emailResult.isSuccess && emailResult.stdout.isNotBlank(),
            name = nameResult.stdout.trim().takeIf { nameResult.isSuccess && it.isNotBlank() },
            email = emailResult.stdout.trim().takeIf { emailResult.isSuccess && it.isNotBlank() }
        )
    }

    /**
     * Get git global config values for pre-population.
     * Returns (name, email) pair with null for missing values.
     * Call from background thread.
     */
    fun getGitConfig(): Pair<String?, String?> {
        val name = runGitConfigCommand("user.name")
        val email = runGitConfigCommand("user.email")
        return name to email
    }

    private fun runGitConfigCommand(key: String): String? = try {
        val commandLine = GeneralCommandLine("git")
            .withParameters("config", "--global", key)
            .withCharset(StandardCharsets.UTF_8)

        val handler = CapturingProcessHandler(commandLine)
        val output = handler.runProcess(TimeUnit.SECONDS.toMillis(5).toInt())

        if (output.exitCode == 0) {
            output.stdout.trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
    } catch (e: Exception) {
        log.debug("Failed to get git config $key", e)
        null
    }

    /**
     * Set jj user configuration values.
     * Call from background thread.
     * @return CommandResult for the last operation (email), or name result if email not provided
     */
    fun setConfig(name: String?, email: String?): CommandExecutor.CommandResult? {
        var result: CommandExecutor.CommandResult? = null

        if (!name.isNullOrBlank()) {
            result = executor.configSetUser("user.name", name)
            if (!result.isSuccess) {
                return result
            }
        }

        if (!email.isNullOrBlank()) {
            result = executor.configSetUser("user.email", email)
        }

        return result
    }
}
