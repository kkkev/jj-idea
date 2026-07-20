package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Shortenable
import java.util.regex.PatternSyntaxException

/**
 * A compiled log search query, with two match semantics chosen by the caller via overload:
 * - [matches] (String) — substring match, for free-text fields (description, author).
 * - [matches] (Shortenable) — prefix match, for ids (change id, commit id). A fragment that
 *   occurs mid-string but isn't a leading prefix does not match, since ids are conventionally
 *   referred to and typed by their leading prefix (jj-idea-odzo).
 *
 * Construct via [create], which returns null for a blank query (no text filter active).
 */
class LogFilterMatcher private constructor(
    private val substringMatch: (String) -> Boolean,
    private val prefixMatch: (String) -> Boolean
) {
    /** Substring semantics — for free-text fields (description, author). */
    fun matches(text: String): Boolean = substringMatch(text)

    /**
     * Prefix semantics — for ids (change id, commit id). Checks BOTH [Shortenable.full] and
     * [Shortenable.short]: for a divergent change id, short (e.g. "abc/2") is not a prefix of
     * full (e.g. "abcdef/2"), so matching only `full` would mean the user-visible short id
     * never matches its own filter.
     */
    fun matches(id: Shortenable): Boolean = prefixMatch(id.full) || prefixMatch(id.short)

    /** Applies the field-level rules for a log row: which fields participate, and how. */
    fun matches(entry: LogEntry): Boolean =
        matches(entry.description.summary) ||
            entry.author?.name?.let(::matches) == true ||
            entry.author?.email?.let(::matches) == true ||
            matches(entry.id) ||
            matches(entry.commitId)

    companion object {
        /** Returns null when [query] is blank, meaning no text filter is active. */
        fun create(query: String, useRegex: Boolean, matchCase: Boolean, wholeWords: Boolean): LogFilterMatcher? {
            if (query.isBlank()) return null
            val ignoreCase = !matchCase

            // Compile the regex once, up front. The only statement here that can throw is an
            // invalid user-entered pattern (PatternSyntaxException); on that, fall back to a
            // literal match rather than letting it propagate.
            val regex = if (useRegex) {
                try {
                    query.toRegex(if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
                } catch (_: PatternSyntaxException) {
                    null
                }
            } else {
                null
            }

            return if (regex != null) {
                LogFilterMatcher(
                    substringMatch = { text -> regex.containsMatchIn(text) },
                    // Anchored at index 0 = prefix match.
                    prefixMatch = { text -> regex.matchesAt(text, 0) }
                )
            } else {
                LogFilterMatcher(
                    substringMatch = literalSubstringMatcher(query, ignoreCase, wholeWords),
                    prefixMatch = { text -> text.startsWith(query, ignoreCase) }
                )
            }
        }

        private fun literalSubstringMatcher(
            query: String,
            ignoreCase: Boolean,
            wholeWords: Boolean
        ): (String) -> Boolean {
            if (wholeWords) {
                val pattern = if (ignoreCase) {
                    "\\b${Regex.escape(query)}\\b".toRegex(RegexOption.IGNORE_CASE)
                } else {
                    "\\b${Regex.escape(query)}\\b".toRegex()
                }
                return { text: String -> pattern.containsMatchIn(text) }
            }

            if (!ignoreCase) {
                return { text: String -> text.contains(query) }
            }

            val lowerQuery = query.lowercase()
            return { text: String -> text.lowercase().contains(lowerQuery) }
        }
    }
}
