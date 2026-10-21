package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revset

/**
 * The log tab's Scope chip (jj-idea-vqpn, GitHub #116; jj-idea-ehwm.4): a per-tab base revset
 * that the search box and the other filters narrow within, ANDed with them
 * ([JujutsuLogTableModel.setScope], compiled by [compileLogFilterRevset]).
 *
 * The repo-level revset from [in.kkkev.jjidea.settings.JujutsuSettings.resolvedLogRevset] keeps
 * governing what every tab and every dialog/picker loads into
 * [in.kkkev.jjidea.jj.JujutsuRepository.logCache]; this filter is ANDed onto it, so it can never
 * surface a commit that revset excludes.
 */

/**
 * The revset to run against jj: [filter] ANDed with the repo-level revset [base] so the
 * filter can never surface a commit [base] itself excludes.
 *
 * [base] blank (jj's [Revset.Default] — "omit -r, let jj's own `revsets.log` config decide") has
 * no expression to AND with, so the query is just [filter] alone and is not bounded by jj's own default.
 */
internal fun revsetFilterQuery(base: Revset, filter: String): Revset = andRevsets(base, listOf(filter))

/**
 * [base] (unless it is [Revset.Default]) ANDed with each of [terms], every operand parenthesised so
 * an operator inside one term (`|`, `~`) can't change how the others bind. [terms] must be non-empty.
 */
internal fun andRevsets(base: Revset, terms: List<String>): Revset {
    val operands = terms.map { "($it)" }
    return Expression((if (base == Revset.Default) operands else listOf("($base)") + operands).joinToString(" & "))
}

/**
 * Checks that [filter] is a valid revset against every repo in [repos], returning jj's error message
 * per failing repo (empty when valid). One `jj log -r ... --limit 1` per repo - O(#repos) processes
 * and one row each, however many commits match; the actual filtering is done by the loader's paged
 * filter window ([UnifiedJujutsuLogDataLoader.applyFilter]). Lets the Revset chip's popup show an
 * inline error before the scope is applied.
 *
 * @param baseFor the repo-level revset each repo's query is ANDed with — normally
 *   [in.kkkev.jjidea.settings.JujutsuSettings.resolvedLogRevset]; a parameter (rather than reading
 *   settings directly) so this stays pure and unit-testable without the platform.
 */
internal fun validateRevsetFilter(
    repos: Collection<JujutsuRepository>,
    filter: String,
    baseFor: (JujutsuRepository) -> Revset
): Map<JujutsuRepository, String> {
    val errors = mutableMapOf<JujutsuRepository, String>()
    for (repo in repos) {
        repo.logService.getLog(revsetFilterQuery(baseFor(repo), filter), limit = 1, quiet = true)
            .onFailure { errors[repo] = it.message ?: it.toString() }
    }
    return errors
}

private val ERROR_COLUMN = Regex("""-->\s*\d+:(\d+)""")
private val ERROR_DETAIL = Regex("""^\s*=\s*(.+)$""")
private val ERROR_CAUSE_START = Regex("""^(\d+):\s*(.*)$""")
private const val CAUSED_BY = "Caused by:"

/** One link of jj's "Caused by:" chain: its first line plus the frame lines that follow it. */
private class ErrorCause(val first: String, val body: MutableList<String> = mutableListOf()) {
    private val isFrame get() = first.startsWith("-->")
    val column get() = if (isFrame) ERROR_COLUMN.find(first)?.groupValues?.get(1) else null
    val detail get() = if (isFrame) {
        body.firstNotNullOfOrNull { ERROR_DETAIL.find(it)?.groupValues?.get(1) }
    } else {
        first.ifEmpty { null }
    }
}

private fun errorCauses(lines: List<String>): List<ErrorCause> {
    val start = lines.indexOfFirst { it.contains(CAUSED_BY) }
    if (start < 0) return emptyList()
    val causes = mutableListOf<ErrorCause>()
    val firstRest = lines[start].substringAfter(CAUSED_BY).trim()
    // A lone cause starts on the "Caused by:" line itself; a chain numbers each cause on the lines below.
    val numbered = lines.drop(start + 1).any { ERROR_CAUSE_START.matches(it) }
    if (!numbered) causes += ErrorCause(firstRest)
    for (line in lines.drop(start + 1)) {
        if (line.startsWith("Hint:")) break
        val m = ERROR_CAUSE_START.find(line)
        if (m != null) causes += ErrorCause(m.groupValues[2].trim()) else causes.lastOrNull()?.body?.add(line)
    }
    return causes
}

/**
 * Boils a jj revset error down to one line for a tooltip or the status bar. jj prints the failing
 * revset back as a framed snippet (`1 | heads(` with `|` gutters and a caret line), which for a
 * compiled log filter would expose the terms other filters contributed; this keeps only the
 * headline (after any `Error from jj log: ` prefix), the innermost cause's explanation (its text, or
 * the `= ...` line of its frame) and the column of the innermost frame, and drops the snippet and the
 * hints. An error raised inside a revset alias has a chain of causes (the alias reference, then the
 * failure within its definition), so the last one is the one that matters. A message with no such
 * structure ("Revision `x` doesn't exist") is returned trimmed, minus any `Error: ` prefix.
 */
internal fun conciseRevsetError(message: String): String {
    val lines = message.lines()
    val headline = lines.firstOrNull { it.contains("Error:") }
        ?.substringAfter("Error:")?.trim()
        ?: lines.firstOrNull { it.isNotBlank() }?.trim()
        ?: return message.trim()
    val causes = errorCauses(lines)
    val detail = causes.lastOrNull()?.detail
    val column = causes.lastOrNull { it.column != null }?.column
    return buildString {
        append(headline)
        if (detail != null && detail != headline) append(": ").append(detail)
        if (column != null) append(" (column ").append(column).append(')')
    }
}
