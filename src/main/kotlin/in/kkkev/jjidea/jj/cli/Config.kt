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

    /**
     * A config value together with where it came from, for display - e.g. "Work Name, from a
     * `--when.repositories` scope in ~/.config/jj/config.toml" (jj-idea-i0e6). [source] and
     * [path] are `null` whenever provenance couldn't be determined (an older `jj` without the
     * `config list -T` keywords used, unparseable output, or a value with no backing file such
     * as a built-in default) - callers should degrade to showing [value] alone.
     */
    data class Resolved(val value: String, val source: String?, val path: String?)

    interface ScopedConfig {
        operator fun get(key: Key): String?
        operator fun set(key: Key, value: String?)

        /** [get] plus provenance. `null` when [key] isn't set at all in this scope. */
        fun resolve(key: Key): Resolved?
    }

    private inner class ScopedConfigImpl(private val scope: CommandExecutor.ConfigScope?) : ScopedConfig {
        // If a scope has been specified, check the config option exists in that scope via listing.
        // Falls back to effective (cross-scope) lookup when no scope filter is active.
        override fun get(key: Key): String? {
            val listedInScope = scope == null ||
                run {
                    val listing = commandExecutor.configList(key.string, scope)
                    listing is CommandExecutor.CommandResult.Success && listing.stdout.isNotBlank()
                }
            if (!listedInScope) return null

            val result = commandExecutor.configGet(key.string)
            if (result !is CommandExecutor.CommandResult.Success) return null
            return result.stdout.trim().takeIf { it.isNotEmpty() }
        }

        override fun resolve(key: Key): Resolved? {
            val value = get(key) ?: return null

            // Provenance is best-effort: any failure or unexpected shape just means the caller
            // shows `value` with no source/path, never a missing row.
            val listing = commandExecutor.configListDetailed(key.string, scope)
            if (listing !is CommandExecutor.CommandResult.Success) return Resolved(value, null, null)
            // Not `.trim()` - the unit-separator delimiter counts as whitespace to
            // `Character.isWhitespace`, so trimming can eat a trailing empty `path` field
            // (a value with no backing file, e.g. a built-in default) along with it.
            val fields = listing.stdout.lineSequence().firstOrNull()?.split(CONFIG_PROVENANCE_DELIMITER)
            if (fields == null || fields.size < 3) return Resolved(value, null, null)
            val (_, source, path) = fields
            return Resolved(value, source.ifBlank { null }, path.ifBlank { null })
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
