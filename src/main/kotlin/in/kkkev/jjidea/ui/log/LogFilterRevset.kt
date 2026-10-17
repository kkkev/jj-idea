package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.jj.logSearchRevset
import `in`.kkkev.jjidea.jj.quoteRevsetString
import kotlinx.datetime.Instant

/** What a Reference filter selection means to jj: `@`, a bookmark (local or remote-only) name, or a tag name. */
internal sealed interface LogFilterReference {
    data object WorkingCopy : LogFilterReference

    data class Bookmark(val name: String) : LogFilterReference

    data class Tag(val name: String) : LogFilterReference
}

/**
 * Everything one log tab filters by, in the shape [compileLogFilterRevset] turns into a revset
 * (jj-idea-ehwm, GitHub #116). Mirrors the filter fields of [JujutsuLogTableModel]; the Root filter
 * is deliberately absent - it picks repositories, not commits, so it stays a client-side choice of
 * which repos to query.
 *
 * @property scope the tab's base revset (the Revset chip), layered on the repo-level revset.
 * @property paths repo-relative file or directory paths, as the Paths chip stores them.
 * @property revsetMode when true, [searchText] is a jj revset instead of text to match.
 */
internal data class LogFilterState(
    val scope: String = "",
    val reference: LogFilterReference? = null,
    val authorEmails: Set<String> = emptySet(),
    val dateCutoff: Instant? = null,
    val paths: Set<String> = emptySet(),
    val searchText: String = "",
    val useRegex: Boolean = false,
    val matchCase: Boolean = false,
    val wholeWords: Boolean = false,
    val revsetMode: Boolean = false
) {
    /** True when at least one term would be compiled, i.e. [compileLogFilterRevset] would not return null. */
    val isActive: Boolean
        get() = scope.isNotBlank() ||
            reference != null ||
            authorEmails.isNotEmpty() ||
            dateCutoff != null ||
            paths.isNotEmpty() ||
            searchText.isNotBlank()
}

/**
 * Compiles [state] to one revset for a single repo: `(base) & (scope) & (reference) & (authors) &
 * (date) & (paths) & (search)`, omitting the terms that aren't active. Returns null when no filter
 * is active, meaning "nothing to narrow, use the plain loaded window".
 *
 * Each chip's term is the true-revset form of what the client-side predicate approximated:
 * - Reference -> `::(<ref>)`, all ancestors (not just those in the loaded window). A bookmark name
 *   matches the local bookmark and any remote bookmark of that name, as the client filter did.
 * - Author -> `author_email(exact:...)` per email, OR-ed.
 * - Date -> `author_date(after:...)`.
 * - Paths -> `files(root:...)` per path, OR-ed (a directory path matches everything under it).
 * - Search -> [logSearchRevset] for text, or the user's own revset in [LogFilterState.revsetMode].
 *
 * [matchIds] = false drops the search term's id-prefix lookup (see [logSearchRevset]).
 *
 * [base] is ANDed in by [andRevsets], so no filter can surface a commit the repo-level revset excludes.
 */
internal fun compileLogFilterRevset(base: Revset, state: LogFilterState, matchIds: Boolean = true): Revset? {
    val terms = buildList {
        state.scope.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
        state.reference?.let { add("::(${referenceTerm(it)})") }
        if (state.authorEmails.isNotEmpty()) {
            add(state.authorEmails.sorted().joinToString(" | ") { "author_email(exact:${quoteRevsetString(it)})" })
        }
        state.dateCutoff?.let { add("author_date(after:${quoteRevsetString(wholeSeconds(it))})") }
        if (state.paths.isNotEmpty()) {
            add(state.paths.sorted().joinToString(" | ") { "files(root:${quoteRevsetString(it)})" })
        }
        searchTerm(state, matchIds)?.let { add(it) }
    }
    return if (terms.isEmpty()) null else andRevsets(base, terms)
}

/**
 * jj's date parser rejects fractional seconds (`...48.895622Z` fails with "expected timezone"), and
 * the Date chip's cutoff is "now minus a period", so truncate to whole seconds.
 */
private fun wholeSeconds(instant: Instant): String = Instant.fromEpochSeconds(instant.epochSeconds).toString()

private fun referenceTerm(reference: LogFilterReference): String = when (reference) {
    LogFilterReference.WorkingCopy -> "@"
    is LogFilterReference.Bookmark -> {
        val name = quoteRevsetString(reference.name)
        "bookmarks(exact:$name) | remote_bookmarks(exact:$name)"
    }
    is LogFilterReference.Tag -> "tags(exact:${quoteRevsetString(reference.name)})"
}

private fun searchTerm(state: LogFilterState, matchIds: Boolean): String? = when {
    state.searchText.isBlank() -> null
    state.revsetMode -> state.searchText.trim()
    else -> logSearchRevset(state.searchText, state.useRegex, state.matchCase, state.wholeWords, matchIds)?.value
}
