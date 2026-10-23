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

/** A configured revset alias. A function alias `f(x, y)` has [params] = `[x, y]`; a symbol alias has none. */
data class RevsetAlias(
    val name: String,
    val isFunction: Boolean = false,
    val params: List<String> = emptyList(),
    /** Config source jj reports (`user`, `repo`, `workspace`, `default`...); null when unknown. */
    val source: String? = null
) {
    /** Defined for this repository or workspace rather than for the user. */
    val isRepoLevel get() = source == "repo" || source == "workspace"
}

private val ALIAS_LINE = Regex("""^revset-aliases\.(?:"((?:[^"\\]|\\.)*)"|([^\s=]+))\s*=""")
private val FUNCTION_ALIAS = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\((.*)\)$""")

/**
 * Parses `jj config list revset-aliases` output. Symbol aliases print as `revset-aliases.name = ...`;
 * function aliases have a quoted key, `revset-aliases."f(x)" = ...`. Lines that are neither are skipped.
 */
fun parseRevsetAliases(output: String): List<RevsetAlias> = output.lineSequence()
    .mapNotNull { ALIAS_LINE.find(it)?.let { m -> aliasFromKey(m.groupValues[1].ifEmpty { m.groupValues[2] }, null) } }
    .distinctBy { it.name to it.isFunction }
    .toList()

private val SOURCE_LINE = Regex("""^revset-aliases\.(?:"((?:[^"\\]|\\.)*)"|([^\s\u001f]+))\u001f(.*)$""")

/** Parses `name<US>source` lines (see [CommandExecutor.configListSources]); the first entry for a name wins. */
fun parseRevsetAliasSources(output: String): List<RevsetAlias> = output.lineSequence()
    .mapNotNull {
        SOURCE_LINE.find(it)?.let { m ->
            aliasFromKey(m.groupValues[1].ifEmpty { m.groupValues[2] }, m.groupValues[3].ifEmpty { null })
        }
    }
    .distinctBy { it.name to it.isFunction }
    .toList()

private fun aliasFromKey(key: String, source: String?): RevsetAlias =
    FUNCTION_ALIAS.matchEntire(key)?.let { m ->
        val params = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        RevsetAlias(m.groupValues[1], true, params, source)
    } ?: RevsetAlias(key, source = source)

/** The configured revset aliases, user and repo, with their source. One `jj config list` process; empty if it fails. */
fun listRevsetAliases(executor: CommandExecutor): List<RevsetAlias> =
    when (val result = executor.configListSources("revset-aliases")) {
        is CommandResult.Success -> parseRevsetAliasSources(result.stdout)
        else -> emptyList()
    }
