package `in`.kkkev.jjidea.jj

/**
 * Builds the revset used by the log search field's Enter action (jj-idea-lpbv) to search the
 * whole repository, not just the commits currently loaded in the log window.
 *
 * Mirrors the field's text-filter toggles ([useRegex]/[matchCase]/[wholeWords]) as jj string
 * patterns, so a commit that the client-side [in.kkkev.jjidea.ui.log.LogFilterMatcher] would
 * match is exactly the set of commits this revset finds:
 * - Plain text -> `substring` / `substring-i` (the `-i` suffix for case-insensitive, the
 *   default).
 * - [useRegex] -> `regex` / `regex-i`, passing the query straight through as the pattern.
 * - [wholeWords] (non-regex) -> also uses `regex`/`regex-i`, wrapping the escaped literal in
 *   `\b...\b` since jj has no whole-word string-pattern kind of its own.
 *
 * `present(<query>)` is prepended when the raw query looks like an id, hash, or bookmark name
 * ([looksLikeRevisionName]) so pasting a Git hash or change-id prefix (jj-idea-odzo) resolves
 * even when it matches no description/author. This guard is deliberately conservative: unlike
 * `description()`/`author()`, an unparseable revision expression is a *syntax* error that would
 * fail the whole revset, not a harmless non-match, so only text that cannot contain revset syntax
 * is ever spliced in as a revision.
 *
 * Returns null for a blank query (nothing to search).
 */
internal fun logSearchRevset(query: String, useRegex: Boolean, matchCase: Boolean, wholeWords: Boolean): Expression? {
    if (query.isBlank()) return null

    val kind = if (matchCase) "regex" else "regex-i"
    val pattern = when {
        useRegex -> query
        wholeWords -> "\\b${escapeRustRegexLiteral(query)}\\b"
        else -> null
    }

    val stringPattern = if (pattern != null) {
        "$kind:${quoteRevsetString(pattern)}"
    } else {
        "${if (matchCase) "substring" else "substring-i"}:${quoteRevsetString(query)}"
    }

    val terms = mutableListOf("description($stringPattern)", "author($stringPattern)")
    if (looksLikeRevisionName(query)) {
        terms.add(0, "present(${quoteRevsetString(query)})")
    }

    return Expression(terms.joinToString(" | "))
}

/**
 * True when [text] contains nothing but characters that can appear in a change id, commit hash,
 * or bookmark/tag name, so it's safe to use as-is as a revset revision expression (e.g. inside
 * `present(...)`). Deliberately excludes whitespace and revset metacharacters (`.`, `|`, `(`,
 * `"`, etc.) so free-form search text is never misinterpreted as revset syntax.
 */
private fun looksLikeRevisionName(text: String) = REVISION_NAME_PATTERN.matches(text)

private val REVISION_NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_/-]*$")

/**
 * Quotes [text] as a jj revset double-quoted string literal, escaping backslashes and quotes.
 */
private fun quoteRevsetString(text: String): String {
    val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

/**
 * Escapes [text] so it matches only as a literal inside a Rust `regex`-crate pattern (jj's string
 * patterns are matched by that crate, not by JVM `java.util.regex`). Deliberately does NOT use
 * Kotlin's [Regex.escape] — that emits Java's `\Q...\E` literal-quoting syntax, which the `regex`
 * crate does not support (it would be interpreted as `\Q`/`\E` escapes, not a literal quote), so
 * every regex metacharacter is escaped individually instead.
 */
private fun escapeRustRegexLiteral(text: String): String {
    val metacharacters = "\\.+*?()|[]{}^$"
    return buildString {
        for (c in text) {
            if (c in metacharacters) append('\\')
            append(c)
        }
    }
}
