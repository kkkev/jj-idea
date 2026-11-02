package `in`.kkkev.jjidea.jj

import `in`.kkkev.jjidea.JujutsuBundle

/** Why [refNameSyntaxError] rejected a bookmark or tag name. [index] is a UTF-16 offset into the name. */
sealed interface RefNameSyntaxError {
    data object Empty : RefNameSyntaxError

    data class InvalidChar(val char: String, val index: Int) : RefNameSyntaxError

    /** A leading, trailing or doubled `.`/`+` separator, or `-` next to `.`/`+`. */
    data class DanglingSeparator(val index: Int) : RefNameSyntaxError

    data object UnterminatedQuote : RefNameSyntaxError
}

private fun isNamePart(cp: Int) =
    Character.isUnicodeIdentifierPart(cp) &&
        !Character.isIdentifierIgnorable(cp) ||
        cp == '_'.code ||
        cp == '*'.code ||
        cp == '/'.code

/**
 * Checks [name] against the symbol grammar jj parses bookmark and tag names with (jj 0.44 `revset.pest`,
 * `symbol_name`): parts of identifier characters joined by `.`, `+` or a run of `-`, or one complete
 * `"…"` / `'…'` literal. Returns null when valid.
 *
 * A lenient superset across jj versions (older jj rejects `a--b`); jj stays the final authority. O(name length).
 */
fun refNameSyntaxError(name: String): RefNameSyntaxError? {
    if (name.isEmpty()) return RefNameSyntaxError.Empty
    if (name[0] == '"' || name[0] == '\'') return quotedError(name)

    var prevSeparator: Char? = null // null at start of name or after a part
    var atStart = true
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        val c = name[i]
        when {
            isNamePart(cp) -> {
                prevSeparator = null
                atStart = false
            }

            c == '.' || c == '+' || c == '-' -> {
                val ok = !atStart && (prevSeparator == null || (prevSeparator == '-' && c == '-'))
                if (!ok) return RefNameSyntaxError.DanglingSeparator(i)
                prevSeparator = c
            }

            else -> return RefNameSyntaxError.InvalidChar(String(Character.toChars(cp)), i)
        }
        i += Character.charCount(cp)
    }
    return if (prevSeparator != null) RefNameSyntaxError.DanglingSeparator(name.length - 1) else null
}

private fun quotedError(name: String): RefNameSyntaxError? {
    val quote = name[0]
    var i = 1
    while (i < name.length && name[i] != quote) {
        // Only double-quoted literals have escapes; single-quoted ones are raw.
        i += if (quote == '"' && name[i] == '\\') 2 else 1
    }
    return when {
        i >= name.length -> RefNameSyntaxError.UnterminatedQuote
        i + 1 < name.length -> RefNameSyntaxError.InvalidChar(name[i + 1].toString(), i + 1)
        else -> null
    }
}

/** User-facing text for [this]; null for [RefNameSyntaxError.Empty], which callers handle themselves. */
fun RefNameSyntaxError.message(): String? = when (this) {
    RefNameSyntaxError.Empty -> null
    is RefNameSyntaxError.InvalidChar -> JujutsuBundle.message("dialog.refname.error.invalid.char", char)
    is RefNameSyntaxError.DanglingSeparator -> JujutsuBundle.message("dialog.refname.error.separator")
    RefNameSyntaxError.UnterminatedQuote -> JujutsuBundle.message("dialog.refname.error.quote")
}
