package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult

/**
 * Saving a log revset as a jj revset alias (jj-idea-ehwm.6, GitHub #116/#150):
 * `revset-aliases.<name> = "<expr>"` in the user or repo config, so the alias also works in the
 * CLI and in every revset input here. Costs one `jj config` process per call (O(1) per repo).
 */

private val ALIAS_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

private const val ALIAS_KEY_PREFIX = "revset-aliases."

/**
 * Whether [name] can be a plain symbol alias. Deliberately narrower than jj's own grammar (no `-`/`.`
 * /`+`, no function aliases like `f(x)`): it also keeps `revset-aliases.<name>` a single unquoted
 * TOML key path, so [saveRevsetAlias] never has to quote the key.
 */
fun isValidRevsetAliasName(name: String) = ALIAS_NAME.matches(name)

/**
 * [value] as a TOML basic string. `jj config set` parses its value argument as TOML and falls back
 * to a bare string only when that fails, so an unencoded `"main"` loses its quotes and `true` or `1`
 * would be stored as a boolean or integer; encoding always yields a string.
 */
fun tomlBasicString(value: String): String = buildString {
    append('"')
    for (c in value) {
        when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c == '\b' -> append("\\b")
            c == '\u000C' -> append("\\f")
            c < ' ' || c == '\u007F' -> append("\\u%04X".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

/** Whether [scope]'s config already defines the alias [name]. */
fun revsetAliasExists(executor: CommandExecutor, scope: CommandExecutor.ConfigScope, name: String): Boolean {
    val result = executor.configList("$ALIAS_KEY_PREFIX$name", scope)
    return result is CommandResult.Success && result.stdout.isNotBlank()
}

/** Writes (or overwrites) the alias [name] = [expression] in [scope]'s config. */
fun saveRevsetAlias(
    executor: CommandExecutor,
    scope: CommandExecutor.ConfigScope,
    name: String,
    expression: String
): CommandResult = executor.configSetUser(scope, "$ALIAS_KEY_PREFIX$name", tomlBasicString(expression))
