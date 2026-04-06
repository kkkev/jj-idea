package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings

class Config(private val commandExecutor: CommandExecutor) {
    val effective: ScopedConfig = ScopedConfigImpl(null)
    val user: ScopedConfig = ScopedConfigImpl(CommandExecutor.ConfigScope.USER)
    val repo: ScopedConfig = ScopedConfigImpl(CommandExecutor.ConfigScope.REPO)

    enum class Key(val string: String) {
        USER_NAME("user.name"),
        USER_EMAIL("user.email")
    }

    interface ScopedConfig {
        operator fun get(key: Key): String?
        operator fun set(key: Key, value: String?)
    }

    private inner class ScopedConfigImpl(private val scope: CommandExecutor.ConfigScope?) : ScopedConfig {
        // If a scope has been specified, check the config option exists in that scope via listing.
        // Falls back to effective (cross-scope) lookup when no scope filter is active.
        override fun get(key: Key): String? =
            if (
                scope != null &&
                commandExecutor.configList(key.string, scope).takeIf { it.isSuccess }?.stdout?.isNotBlank() != true
            ) {
                null
            } else {
                commandExecutor.configGet(key.string).takeIf { it.isSuccess }?.stdout?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }

        override fun set(key: Key, value: String?) {
            val actualScope = scope ?: CommandExecutor.ConfigScope.REPO
            if (value == null) {
                commandExecutor.configUnset(actualScope, key.string)
            } else {
                commandExecutor.configSetUser(actualScope, key.string, value)
            }
        }
    }
}

val JujutsuRepository.config get() = Config(commandExecutor)

val rootlessConfig = Config(
    CliExecutor.forRootlessOperations {
        JujutsuApplicationSettings.getInstance().state.jjExecutablePath.ifBlank { "jj" }
    }
)
