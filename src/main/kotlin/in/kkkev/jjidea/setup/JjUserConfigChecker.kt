package `in`.kkkev.jjidea.setup

import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.cli.Config
import `in`.kkkev.jjidea.jj.cli.config

/**
 * Checks and configures jj user settings (user.name, user.email).
 *
 * Used during initial setup to detect missing user configuration and pre-populate
 * values from git global config if available.
 */
class JjUserConfigChecker(repo: JujutsuRepository) {
    private val config = repo.config.effective

    /**
     * Configuration status for jj user settings.
     */
    data class ConfigStatus(val name: String?, val email: String?) {
        val isComplete: Boolean get() = name != null && email != null
    }

    /**
     * Check current jj user configuration.
     * Call from background thread.
     */
    fun checkConfig() = ConfigStatus(config[Config.Key.USER_NAME], config[Config.Key.USER_EMAIL])
}
