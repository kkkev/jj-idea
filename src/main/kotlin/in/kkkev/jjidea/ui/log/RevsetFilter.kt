package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revset

/**
 * The custom revset log filter (jj-idea-vqpn, GitHub #116): a per-tab revset chip that narrows
 * the log to commits matching a user-typed revset, ANDed with the other filters
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
