package `in`.kkkev.jjidea.jj.revset

import `in`.kkkev.jjidea.jj.cli.RevsetAlias

/** What a completion candidate is, for ordering and the type label. */
enum class RevsetCandidateKind(val label: String?) {
    FUNCTION(null),
    ALIAS("alias"),
    BOOKMARK("bookmark"),
    TAG("tag")
}

data class RevsetCandidate(
    val text: String,
    val kind: RevsetCandidateKind,
    val tail: String = "",
    val takesArgs: Boolean = false
)

/** The symbol characters directly before [offset]: the word being completed. May be empty. */
fun completionPrefix(text: CharSequence, offset: Int): String {
    var start = offset.coerceIn(0, text.length)
    // `.-+` join segments, but `..` is an operator, so only a single one belongs to the symbol.
    while (start > 0 && (isRevsetSymbolChar(text[start - 1]) || isJoiner(text, start - 1))) start--
    return text.substring(start, offset.coerceIn(0, text.length))
}

private fun isJoiner(text: CharSequence, i: Int) = text[i] in "-+" ||
    text[i] == '.' &&
    (i == 0 || text[i - 1] != '.') &&
    (i + 1 >= text.length || text[i + 1] != '.')

/**
 * The candidates for [prefix]: functions first, then aliases, bookmarks and tags. Only candidates
 * that do not contain [prefix] (ignoring case) are dropped; ranking and hump matching are left to the
 * platform matcher. Names are deduplicated within a kind; a function alias `f(x)` is offered as a
 * function with its params as tail text.
 */
fun revsetCandidates(
    prefix: String,
    functions: List<RevsetFunction>,
    bookmarks: Collection<String>,
    tags: Collection<String>,
    aliases: List<RevsetAlias>
): List<RevsetCandidate> {
    val builtin = functions.map { RevsetCandidate(it.name, RevsetCandidateKind.FUNCTION, it.signature, it.takesArgs) }
    val aliased = aliases.map {
        if (it.isFunction) {
            RevsetCandidate(
                it.name,
                RevsetCandidateKind.FUNCTION,
                it.params.joinToString(", ", "(", ")"),
                it.params.isNotEmpty()
            )
        } else {
            RevsetCandidate(it.name, RevsetCandidateKind.ALIAS)
        }
    }
    val refs = bookmarks.map { RevsetCandidate(it, RevsetCandidateKind.BOOKMARK) } +
        tags.map { RevsetCandidate(it, RevsetCandidateKind.TAG) }
    return (builtin + aliased + refs)
        .distinctBy { it.text to it.kind }
        .filter { prefix.isEmpty() || it.text.contains(prefix, ignoreCase = true) }
}
